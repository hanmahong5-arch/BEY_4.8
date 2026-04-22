package com.aionemu.gameserver.services.achievement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.utils.BroadcastUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

/**
 * 成就服务 — milestone-based achievement system.
 *
 * <p><b>核心机制</b>:
 * 每个成就由一个 {@link Trigger} + threshold 定义。外部服务在发生事件时调用
 * {@link #onEvent(int, Trigger, int)} 更新内部累加计数，一旦计数达到阈值则解锁该成就，
 * 向玩家推送通知、广播（可选）、写审计日志。
 *
 * <p><b>持久化</b>:
 * <ul>
 *   <li>每小时 flush 到 {@code log/custom_achievements.json}。</li>
 *   <li>JVM shutdown hook 终次 flush。</li>
 *   <li>重启加载后累加已发生事件（不重复解锁，因 BitSet 防重）。</li>
 * </ul>
 *
 * <p>触发点已植入:
 * <ul>
 *   <li>{@code PvpSeasonService.onPvpKill} → {@link Trigger#PVP_KILL}</li>
 *   <li>{@code SoloFortressService.onSoloCapture} → {@link Trigger#FORTRESS_CAPTURE} 与
 *       {@link Trigger#FORTRESS_CONCURRENT}（当前持有数）</li>
 *   <li>{@code SoloFortressService.onLordKilled} → {@link Trigger#LORD_KILL}</li>
 *   <li>{@code FfaModeService.trackKillerStreak} → {@link Trigger#FFA_STREAK}（传 streak 值）</li>
 * </ul>
 */
public final class AchievementService {

	private static final Logger log = LoggerFactory.getLogger(AchievementService.class);
	private static final AchievementService INSTANCE = new AchievementService();

	public enum Trigger { PVP_KILL, FORTRESS_CAPTURE, FORTRESS_CONCURRENT, LORD_KILL, FFA_STREAK }

	/** Catalog: achievementId → definition. Insertion order preserved. */
	private static final Map<Integer, Achievement> CATALOG = new LinkedHashMap<>();
	static {
		add(1,  "初啼",      "首次击杀敌对玩家",          Trigger.PVP_KILL,            1);
		add(2,  "百夫长",    "累计 PvP 击杀 100 人",      Trigger.PVP_KILL,            100);
		add(3,  "传奇杀神",  "累计 PvP 击杀 1000 人",     Trigger.PVP_KILL,            1000);
		add(4,  "登基",      "首次独占要塞",               Trigger.FORTRESS_CAPTURE,    1);
		add(5,  "双冠",      "同时独占 2 座要塞",          Trigger.FORTRESS_CONCURRENT, 2);
		add(6,  "三冠",      "同时独占 3 座要塞",          Trigger.FORTRESS_CONCURRENT, 3);
		add(7,  "五冠",      "同时独占 5 座要塞 — 君临天下", Trigger.FORTRESS_CONCURRENT, 5);
		add(8,  "狂血",      "FFA 连杀 10 人",             Trigger.FFA_STREAK,          10);
		add(9,  "屠神",      "击杀要塞领主 10 次",         Trigger.LORD_KILL,           10);
		add(10, "弑君",      "击杀要塞领主 50 次",         Trigger.LORD_KILL,           50);
	}
	private static void add(int id, String name, String desc, Trigger t, int threshold) {
		CATALOG.put(id, new Achievement(id, name, desc, t, threshold));
	}

	/** playerObjectId → BitSet of unlocked achievement ids. */
	private final ConcurrentMap<Integer, BitSet> unlocked = new ConcurrentHashMap<>();

	/** playerObjectId → (triggerOrdinal → counter). For cumulative (non-streak) triggers. */
	private final ConcurrentMap<Integer, AtomicInteger[]> counters = new ConcurrentHashMap<>();

	private static final Path STATE_PATH = Paths.get("log", "custom_achievements.json");

	private volatile boolean initialized;

	private AchievementService() {}

	public static AchievementService getInstance() {
		return INSTANCE;
	}

	public synchronized void init() {
		if (initialized) return;
		initialized = true;
		loadState();
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveState, "AchievementFlush"));
		log.info("[Achievement] initialized, {} definitions, {} players with progress",
			CATALOG.size(), unlocked.size());
	}

	/**
	 * Main entry point — called from existing services when their triggers fire.
	 *
	 * @param playerId  the player who triggered the event
	 * @param trigger   which trigger fired
	 * @param value     for PVP_KILL / LORD_KILL: always 1 (cumulative); for
	 *                  FORTRESS_CONCURRENT / FFA_STREAK: the current absolute value
	 *                  to compare against thresholds
	 */
	public void onEvent(int playerId, Trigger trigger, int value) {
		if (!CustomConfig.ACHIEVEMENT_ENABLED || playerId == 0 || trigger == null)
			return;
		try {
			int current;
			if (trigger == Trigger.PVP_KILL || trigger == Trigger.LORD_KILL ||
				trigger == Trigger.FORTRESS_CAPTURE) {
				// Cumulative: increment counter, then compare
				AtomicInteger[] arr = counters.computeIfAbsent(playerId,
					k -> {
						AtomicInteger[] a = new AtomicInteger[Trigger.values().length];
						for (int i = 0; i < a.length; i++) a[i] = new AtomicInteger();
						return a;
					});
				current = arr[trigger.ordinal()].addAndGet(Math.max(1, value));
			} else {
				// Absolute: the value itself is what we compare
				current = value;
			}
			checkUnlocks(playerId, trigger, current);
		} catch (Throwable t) {
			log.error("[Achievement] onEvent failed for player " + playerId, t);
		}
	}

	private void checkUnlocks(int playerId, Trigger trigger, int currentValue) {
		BitSet playerBits = unlocked.computeIfAbsent(playerId, k -> new BitSet());
		for (Achievement ach : CATALOG.values()) {
			if (ach.trigger != trigger) continue;
			if (currentValue < ach.threshold) continue;
			synchronized (playerBits) {
				if (playerBits.get(ach.id)) continue;
				playerBits.set(ach.id);
			}
			unlock(playerId, ach);
		}
	}

	private void unlock(int playerId, Achievement ach) {
		Player online = World.getInstance().getPlayer(playerId);
		String playerName = online != null ? online.getName() : "#" + playerId;
		String msg = "[成就解锁] 『" + ach.name + "』— " + ach.desc;
		if (online != null) {
			PacketSendUtility.sendPacket(online,
				new SM_MESSAGE(0, null, msg, ChatType.BRIGHT_YELLOW_CENTER));
		}
		// Broadcast high-tier achievements globally
		if (ach.threshold >= 100 || ach.trigger == Trigger.FORTRESS_CONCURRENT) {
			String bcast = "『" + BroadcastUtil.sanitize(playerName) + "』已成就: " + ach.name;
			BroadcastUtil.broadcastYellow(bcast);
		}
		CustomFeatureMetrics.getInstance().inc("achievement.unlocked");
		CustomFeatureMetrics.getInstance().inc("achievement.unlocked." + ach.id);
		CustomAuditLog.getInstance().log("achievement", "unlock", playerName,
			"id=" + ach.id + " name=" + ach.name);
		log.info("[Achievement] {} unlocked '{}' (id={})", playerName, ach.name, ach.id);
	}

	/** Get list of unlocked achievements for a player. */
	public List<Achievement> getUnlocked(int playerId) {
		BitSet bits = unlocked.get(playerId);
		if (bits == null) return List.of();
		List<Achievement> out = new ArrayList<>();
		for (Achievement ach : CATALOG.values()) {
			if (bits.get(ach.id)) out.add(ach);
		}
		return out;
	}

	/** Full catalog (for //ach catalog). */
	public List<Achievement> getCatalog() {
		return new ArrayList<>(CATALOG.values());
	}

	/** GM grant: directly unlock an achievement. Returns true if newly applied. */
	public boolean adminGrant(int playerId, int achievementId, String gmName) {
		Achievement ach = CATALOG.get(achievementId);
		if (ach == null) return false;
		BitSet playerBits = unlocked.computeIfAbsent(playerId, k -> new BitSet());
		synchronized (playerBits) {
			if (playerBits.get(achievementId)) return false;
			playerBits.set(achievementId);
		}
		unlock(playerId, ach);
		CustomAuditLog.getInstance().logGm("ach grant", gmName,
			"player=" + playerId + " id=" + achievementId);
		return true;
	}

	/** GM revoke: remove an unlocked achievement. */
	public boolean adminRevoke(int playerId, int achievementId, String gmName) {
		BitSet bits = unlocked.get(playerId);
		if (bits == null || !bits.get(achievementId)) return false;
		synchronized (bits) { bits.clear(achievementId); }
		CustomAuditLog.getInstance().logGm("ach revoke", gmName,
			"player=" + playerId + " id=" + achievementId);
		return true;
	}

	/** Count of players with at least one achievement. */
	public int playerCount() {
		return (int) unlocked.values().stream().filter(b -> !b.isEmpty()).count();
	}

	// ────────────── Persistence ──────────────

	private synchronized void saveState() {
		try {
			Files.createDirectories(STATE_PATH.getParent());
			StringBuilder sb = new StringBuilder("{\n  \"players\": {\n");
			// Filter non-empty first, then emit with proper comma handling (avoids trailing comma)
			List<Map.Entry<Integer, BitSet>> players = new ArrayList<>();
			for (var e : unlocked.entrySet()) {
				if (!e.getValue().isEmpty()) players.add(e);
			}
			for (int k = 0; k < players.size(); k++) {
				var e = players.get(k);
				BitSet bits = e.getValue();
				sb.append("    \"").append(e.getKey()).append("\": [");
				boolean first = true;
				for (int id = bits.nextSetBit(0); id >= 0; id = bits.nextSetBit(id + 1)) {
					if (!first) sb.append(",");
					sb.append(id);
					first = false;
				}
				sb.append("]");
				if (k < players.size() - 1) sb.append(",");
				sb.append("\n");
			}
			sb.append("  },\n  \"counters\": {\n");
			List<Map.Entry<Integer, AtomicInteger[]>> cList = new ArrayList<>(counters.entrySet());
			for (int k = 0; k < cList.size(); k++) {
				var e = cList.get(k);
				AtomicInteger[] arr = e.getValue();
				sb.append("    \"").append(e.getKey()).append("\": [");
				for (int j = 0; j < arr.length; j++) {
					if (j > 0) sb.append(",");
					sb.append(arr[j].get());
				}
				sb.append("]");
				if (k < cList.size() - 1) sb.append(",");
				sb.append("\n");
			}
			sb.append("  }\n}\n");
			Files.writeString(STATE_PATH, sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("[Achievement] saveState failed: {}", e.getMessage());
		}
	}

	private void loadState() {
		if (!Files.exists(STATE_PATH)) return;
		try {
			String content = Files.readString(STATE_PATH, StandardCharsets.UTF_8);
			parsePlayers(content);
			parseCounters(content);
		} catch (Exception e) {
			log.warn("[Achievement] loadState failed: {}", e.getMessage());
		}
	}

	private void parsePlayers(String content) {
		int start = content.indexOf("\"players\"");
		if (start < 0) return;
		int open = content.indexOf('{', start);
		int close = matchingBrace(content, open);
		if (open < 0 || close < 0) return;
		String body = content.substring(open + 1, close);
		int i = 0;
		while (i < body.length()) {
			int q1 = body.indexOf('"', i);
			if (q1 < 0) break;
			int q2 = body.indexOf('"', q1 + 1);
			if (q2 < 0) break;
			int br1 = body.indexOf('[', q2);
			int br2 = body.indexOf(']', br1);
			if (br1 < 0 || br2 < 0) break;
			try {
				int playerId = Integer.parseInt(body.substring(q1 + 1, q2));
				String arr = body.substring(br1 + 1, br2).trim();
				BitSet bits = new BitSet();
				if (!arr.isEmpty()) {
					for (String idStr : arr.split(",")) {
						bits.set(Integer.parseInt(idStr.trim()));
					}
				}
				unlocked.put(playerId, bits);
			} catch (NumberFormatException ignored) {}
			i = br2 + 1;
		}
	}

	private void parseCounters(String content) {
		int start = content.indexOf("\"counters\"");
		if (start < 0) return;
		int open = content.indexOf('{', start);
		int close = matchingBrace(content, open);
		if (open < 0 || close < 0) return;
		String body = content.substring(open + 1, close);
		int i = 0;
		while (i < body.length()) {
			int q1 = body.indexOf('"', i);
			if (q1 < 0) break;
			int q2 = body.indexOf('"', q1 + 1);
			if (q2 < 0) break;
			int br1 = body.indexOf('[', q2);
			int br2 = body.indexOf(']', br1);
			if (br1 < 0 || br2 < 0) break;
			try {
				int playerId = Integer.parseInt(body.substring(q1 + 1, q2));
				String[] parts = body.substring(br1 + 1, br2).split(",");
				AtomicInteger[] arr = new AtomicInteger[Trigger.values().length];
				for (int k = 0; k < arr.length; k++) {
					arr[k] = new AtomicInteger(k < parts.length ? Integer.parseInt(parts[k].trim()) : 0);
				}
				counters.put(playerId, arr);
			} catch (NumberFormatException ignored) {}
			i = br2 + 1;
		}
	}

	private static int matchingBrace(String s, int openIdx) {
		if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '{') return -1;
		int depth = 0;
		for (int i = openIdx; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '{') depth++;
			else if (c == '}') { depth--; if (depth == 0) return i; }
		}
		return -1;
	}

	/** Immutable achievement definition. */
	public static final class Achievement {
		public final int id;
		public final String name;
		public final String desc;
		public final Trigger trigger;
		public final int threshold;
		Achievement(int id, String name, String desc, Trigger trigger, int threshold) {
			this.id = id;
			this.name = name;
			this.desc = desc;
			this.trigger = trigger;
			this.threshold = threshold;
		}
	}
}
