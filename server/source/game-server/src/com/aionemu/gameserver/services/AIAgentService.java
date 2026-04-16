package com.aionemu.gameserver.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AIAgentConfig;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * AI Agent integration — bidirectional bridge between game events and an
 * external AI endpoint.
 *
 * <h3>Java 25 features used</h3>
 * <ul>
 *   <li><b>Records</b> — immutable event carriers, captured on game thread</li>
 *   <li><b>Sealed interface</b> — compiler-enforced exhaustive event hierarchy</li>
 *   <li><b>Virtual threads</b> — lightweight HTTP pool, no fixed-size tuning</li>
 *   <li><b>Pattern matching</b> — in push response dispatch</li>
 * </ul>
 *
 * <h3>Performance contract</h3>
 * Game-thread cost per event: one {@code record} allocation (~40 bytes) +
 * one {@link Semaphore#tryAcquire()} + one virtual-thread submit. Total ~200ns.
 * All JSON serialization and HTTP I/O run on virtual threads.
 *
 * <h3>Failure isolation</h3>
 * Circuit breaker auto-disables after 5 consecutive failures for 60s.
 * Virtual threads are daemon — they cannot prevent JVM shutdown.
 * Semaphore caps in-flight requests at 4 — backpressure via drop.
 */
public class AIAgentService {

	private static final Logger log = LoggerFactory.getLogger(AIAgentService.class);
	private static final String SENDER = "\u62fe\u5149AI"; // 拾光AI

	private ExecutorService httpPool;
	private HttpClient httpClient;
	private URI eventEndpoint;

	// Backpressure: drop events if too many HTTP requests in-flight
	private final Semaphore inFlight = new Semaphore(4);

	private final RateLimiter rateLimiter = new RateLimiter();
	private final CircuitBreaker circuitBreaker = new CircuitBreaker();

	// ======================== Data Carriers ========================

	/**
	 * Immutable snapshot of a player's state at the moment an event fires.
	 * Captured on the game thread (single-threaded); safe for cross-thread use.
	 */
	record PlayerSnapshot(int id, String name, int level, String cls, String race,
						  int mapId, int x, int y, int z) {

		static PlayerSnapshot capture(Player p) {
			return new PlayerSnapshot(
				p.getObjectId(), p.getName(), p.getLevel(),
				p.getPlayerClass().name(), p.getRace().name(),
				p.getWorldId(), (int) p.getX(), (int) p.getY(), (int) p.getZ());
		}

		void writeJson(StringBuilder sb) {
			sb.append("{\"id\":").append(id)
			  .append(",\"name\":\"").append(Json.esc(name))
			  .append("\",\"level\":").append(level)
			  .append(",\"class\":\"").append(cls)
			  .append("\",\"race\":\"").append(race).append("\"}");
		}

		void writeWorldJson(StringBuilder sb) {
			sb.append(",\"world\":{\"map\":").append(mapId)
			  .append(",\"x\":").append(x)
			  .append(",\"y\":").append(y)
			  .append(",\"z\":").append(z).append('}');
		}
	}

	// ======================== Event Hierarchy ========================

	/**
	 * Sealed event hierarchy — each subtype knows how to serialize itself.
	 * Adding a new event type is: define the record, add a notify method.
	 * The compiler will enforce that {@code toJson()} is implemented.
	 */
	sealed interface GameEvent {
		PlayerSnapshot player();
		String toJson();
	}

	record NpcKill(PlayerSnapshot player, int npcId, String npcName, int npcLevel,
				   long xp, int dp, int ap) implements GameEvent {
		@Override public String toJson() {
			var sb = new StringBuilder(256);
			sb.append("{\"event\":\"npc_kill\",\"player\":");
			player.writeJson(sb);
			sb.append(",\"npc\":{\"id\":").append(npcId)
			  .append(",\"name\":\"").append(Json.esc(npcName))
			  .append("\",\"level\":").append(npcLevel).append('}');
			sb.append(",\"reward\":{\"xp\":").append(xp)
			  .append(",\"dp\":").append(dp)
			  .append(",\"ap\":").append(ap).append('}');
			player.writeWorldJson(sb);
			return sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}').toString();
		}
	}

	record LevelUp(PlayerSnapshot player, int oldLevel, int newLevel) implements GameEvent {
		@Override public String toJson() {
			var sb = new StringBuilder(192);
			sb.append("{\"event\":\"level_up\",\"player\":");
			player.writeJson(sb);
			sb.append(",\"old_level\":").append(oldLevel).append(",\"new_level\":").append(newLevel);
			player.writeWorldJson(sb);
			return sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}').toString();
		}
	}

	record QuestComplete(PlayerSnapshot player, int questId, String questName,
						 int repeat) implements GameEvent {
		@Override public String toJson() {
			var sb = new StringBuilder(224);
			sb.append("{\"event\":\"quest_complete\",\"player\":");
			player.writeJson(sb);
			sb.append(",\"quest\":{\"id\":").append(questId)
			  .append(",\"name\":\"").append(Json.esc(questName))
			  .append("\",\"repeat\":").append(repeat).append('}');
			player.writeWorldJson(sb);
			return sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}').toString();
		}
	}

	record PlayerDeath(PlayerSnapshot player, String killerName,
					   String killerType) implements GameEvent {
		@Override public String toJson() {
			var sb = new StringBuilder(192);
			sb.append("{\"event\":\"player_death\",\"player\":");
			player.writeJson(sb);
			sb.append(",\"killer\":{\"name\":\"").append(Json.esc(killerName))
			  .append("\",\"type\":\"").append(killerType).append("\"}");
			player.writeWorldJson(sb);
			return sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}').toString();
		}
	}

	record ZoneEnter(PlayerSnapshot player, int mapId, String zoneName) implements GameEvent {
		@Override public String toJson() {
			var sb = new StringBuilder(192);
			sb.append("{\"event\":\"zone_enter\",\"player\":");
			player.writeJson(sb);
			sb.append(",\"zone\":{\"map\":").append(mapId)
			  .append(",\"name\":\"").append(Json.esc(zoneName)).append("\"}");
			player.writeWorldJson(sb);
			return sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}').toString();
		}
	}

	// ======================== Public API (stable signatures) ========================

	public void notifyNpcKill(Player player, Npc npc, float dmgPct,
							  long xp, int dp, float ap) {
		if (!canDispatch(AIAgentConfig.EVENT_NPC_KILL) || !rateLimiter.tryAcquire(player))
			return;
		dispatch(new NpcKill(
			PlayerSnapshot.capture(player),
			npc.getNpcId(), npc.getName(), npc.getLevel(), xp, dp, (int) ap));
	}

	public void notifyLevelUp(Player player, int oldLevel, int newLevel) {
		if (!canDispatch(AIAgentConfig.EVENT_LEVEL_UP))
			return;
		dispatch(new LevelUp(PlayerSnapshot.capture(player), oldLevel, newLevel));
	}

	public void notifyQuestComplete(Player player, int questId, String questName, int repeatCount) {
		if (!canDispatch(AIAgentConfig.EVENT_QUEST_COMPLETE) || !rateLimiter.tryAcquire(player))
			return;
		dispatch(new QuestComplete(
			PlayerSnapshot.capture(player), questId, questName, repeatCount));
	}

	public void notifyPlayerDeath(Player player, Object killer, String killerName) {
		if (!canDispatch(AIAgentConfig.EVENT_PLAYER_DEATH))
			return;
		String ktype = switch (killer) {
			case Npc _ -> "npc";
			case Player _ -> "player";
			default -> "other";
		};
		dispatch(new PlayerDeath(PlayerSnapshot.capture(player), killerName, ktype));
	}

	public void notifyZoneEnter(Player player, int mapId, String zoneName) {
		if (!canDispatch(AIAgentConfig.EVENT_ZONE_ENTER) || !rateLimiter.tryZone(player))
			return;
		dispatch(new ZoneEnter(PlayerSnapshot.capture(player), mapId, zoneName));
	}

	// ======================== Push Methods ========================

	public void pushChat(Player player, String message) {
		PacketSendUtility.sendPacket(player,
			new SM_MESSAGE(0, SENDER, message, ChatType.GOLDEN_YELLOW));
	}

	public void pushChat(Player player, String message, ChatType chatType) {
		PacketSendUtility.sendPacket(player,
			new SM_MESSAGE(0, SENDER, message, chatType));
	}

	public void pushHtml(Player player, String html) {
		HTMLService.showHTML(player, html);
	}

	public void pushBroadcast(String message) {
		World.getInstance().forEachPlayer(p -> pushChat(p, message));
	}

	public void assignDynamicTitle(Player player, int titleId) {
		if (!AIAgentConfig.DYNAMIC_TITLES) return;
		if (titleId < AIAgentConfig.TITLE_ID_START
			|| titleId >= AIAgentConfig.TITLE_ID_START + AIAgentConfig.TITLE_ID_COUNT) return;
		player.getTitleList().addTitle(titleId, false, 0);
	}

	// ======================== Core Pipeline ========================

	public void init() {
		if (!AIAgentConfig.ENABLE) {
			log.info("[AIAgent] Disabled");
			return;
		}
		// Virtual threads: lightweight, scales automatically, no pool sizing needed
		httpPool = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("aiagent-http-", 0).factory());
		httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(AIAgentConfig.TIMEOUT_MS))
			.build();
		eventEndpoint = URI.create(AIAgentConfig.API_URL + "/api/player-event");
		log.info("[AIAgent] Ready — endpoint: {}, cooldown: {}s, semaphore: {}",
			eventEndpoint, AIAgentConfig.COOLDOWN_SECONDS, inFlight.availablePermits());
	}

	private boolean canDispatch(boolean eventSwitch) {
		return eventSwitch && AIAgentConfig.ENABLE && httpClient != null && circuitBreaker.isClosed();
	}

	/**
	 * Unified dispatch: snapshot → virtual thread → JSON → HTTP → game-thread push.
	 * Backpressure: drops event if 4 requests already in-flight (tryAcquire).
	 */
	private void dispatch(GameEvent event) {
		if (!inFlight.tryAcquire()) return;

		httpPool.execute(() -> {
			try {
				var json = event.toJson();
				var request = HttpRequest.newBuilder()
					.uri(eventEndpoint)
					.header("Content-Type", "application/json")
					.timeout(Duration.ofMillis(AIAgentConfig.TIMEOUT_MS))
					.POST(HttpRequest.BodyPublishers.ofString(json))
					.build();

				var resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				circuitBreaker.onSuccess();

				if (resp.statusCode() == 200) {
					int pid = event.player().id();
					String body = resp.body();
					// Push back on game thread (single-thread safety)
					ThreadPoolManager.getInstance().execute(() -> applyPush(pid, body));
				}
			} catch (java.net.ConnectException _) {
				circuitBreaker.onFailure("unreachable");
			} catch (java.net.http.HttpTimeoutException _) {
				circuitBreaker.onFailure("timeout");
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				circuitBreaker.onFailure(e.getMessage());
			} finally {
				inFlight.release();
				rateLimiter.maybeCleanup();
			}
		});
	}

	/**
	 * Apply AI push response to a player. Runs on game thread only.
	 */
	private void applyPush(int playerObjId, String body) {
		var player = World.getInstance().getPlayer(playerObjId);
		if (player == null || !player.isOnline()) return;

		var push = Json.str(body, "push");
		var message = Json.str(body, "message");
		if (push == null || message == null || message.isEmpty()) return;

		switch (push) {
			case "chat"        -> pushChat(player, message);
			case "html"        -> pushHtml(player, message);
			case "chat_center" -> pushChat(player, message, ChatType.YELLOW_CENTER);
			case "broadcast"   -> pushBroadcast(message);
			case "title"       -> {
				var tid = Json.str(body, "title_id");
				if (tid != null) {
					try { assignDynamicTitle(player, Integer.parseInt(tid)); }
					catch (NumberFormatException _) { /* malformed title_id from AI */ }
				}
			}
			default -> log.debug("[AIAgent] Unknown push type: {}", push);
		}
	}

	public void shutdown() {
		if (httpPool != null) {
			httpPool.shutdown();
			try { httpPool.awaitTermination(3, TimeUnit.SECONDS); }
			catch (InterruptedException _) { Thread.currentThread().interrupt(); }
		}
		rateLimiter.clear();
		log.info("[AIAgent] Shut down");
	}

	// ======================== Rate Limiter ========================

	/**
	 * Per-player event rate limiter. Prevents notification spam.
	 * Uses two separate maps: general events (30s) and zone events (5s).
	 */
	private static final class RateLimiter {
		private static final long ZONE_COOLDOWN_MS = 5_000;
		private static final long CLEANUP_INTERVAL_MS = 300_000;

		private final Map<Integer, Long> general = new ConcurrentHashMap<>();
		private final Map<Integer, Long> zone = new ConcurrentHashMap<>();
		private volatile long lastCleanup = System.currentTimeMillis();

		boolean tryAcquire(Player player) {
			long now = System.currentTimeMillis();
			long cdMs = AIAgentConfig.COOLDOWN_SECONDS * 1000L;
			Long last = general.get(player.getObjectId());
			if (last != null && now - last < cdMs) return false;
			general.put(player.getObjectId(), now);
			return true;
		}

		boolean tryZone(Player player) {
			long now = System.currentTimeMillis();
			Long last = zone.get(player.getObjectId());
			if (last != null && now - last < ZONE_COOLDOWN_MS) return false;
			zone.put(player.getObjectId(), now);
			return true;
		}

		void maybeCleanup() {
			long now = System.currentTimeMillis();
			if (now - lastCleanup < CLEANUP_INTERVAL_MS) return;
			lastCleanup = now;
			long stale = now - CLEANUP_INTERVAL_MS;
			general.values().removeIf(ts -> ts < stale);
			zone.values().removeIf(ts -> ts < stale);
		}

		void clear() { general.clear(); zone.clear(); }
	}

	// ======================== Circuit Breaker ========================

	/**
	 * Trips after N consecutive failures, auto-resets after a backoff period.
	 * Prevents hammering a dead endpoint.
	 */
	private static final class CircuitBreaker {
		private static final int THRESHOLD = 5;
		private static final long BACKOFF_MS = 60_000;

		private final AtomicInteger failures = new AtomicInteger(0);
		private volatile long openUntil;

		boolean isClosed() {
			if (openUntil == 0) return true;
			if (System.currentTimeMillis() >= openUntil) {
				// Half-open: allow one probe request
				openUntil = 0;
				failures.set(0);
				log.info("[AIAgent] Circuit breaker reset, resuming");
				return true;
			}
			return false;
		}

		void onSuccess() { failures.set(0); }

		void onFailure(String reason) {
			int n = failures.incrementAndGet();
			if (n >= THRESHOLD) {
				openUntil = System.currentTimeMillis() + BACKOFF_MS;
				log.warn("[AIAgent] Circuit OPEN after {} failures ({}), pause {}s",
					n, reason, BACKOFF_MS / 1000);
			}
		}
	}

	// ======================== JSON Utilities ========================

	/** Minimal, zero-dependency JSON helpers. */
	private static final class Json {

		/** Escape a string for safe JSON embedding. Fast path avoids allocation. */
		static String esc(String s) {
			if (s == null) return "";
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '"' || c == '\\' || c < 0x20) {
					return escSlow(s, i); // rare path
				}
			}
			return s; // common path: no alloc
		}

		private static String escSlow(String s, int firstSpecial) {
			var sb = new StringBuilder(s.length() + 8);
			sb.append(s, 0, firstSpecial);
			for (int i = firstSpecial; i < s.length(); i++) {
				char c = s.charAt(i);
				switch (c) {
					case '"'  -> sb.append("\\\"");
					case '\\' -> sb.append("\\\\");
					case '\n' -> sb.append("\\n");
					case '\r' -> sb.append("\\r");
					case '\t' -> sb.append("\\t");
					default   -> { if (c < 0x20) sb.append("\\u%04x".formatted((int) c)); else sb.append(c); }
				}
			}
			return sb.toString();
		}

		/** Extract a string value from a flat JSON object. */
		static String str(String json, String key) {
			var needle = "\"" + key + "\":\"";
			int i = json.indexOf(needle);
			if (i < 0) return null;
			i += needle.length();
			int j = json.indexOf('"', i);
			if (j < 0) return null;
			var raw = json.substring(i, j);
			// Unescape only if needed (fast path)
			if (raw.indexOf('\\') < 0) return raw;
			return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
		}
	}

	// ======================== Singleton ========================

	private AIAgentService() {}
	private static final AIAgentService INSTANCE = new AIAgentService();
	public static AIAgentService getInstance() { return INSTANCE; }
}
