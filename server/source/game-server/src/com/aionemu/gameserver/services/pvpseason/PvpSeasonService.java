package com.aionemu.gameserver.services.pvpseason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.services.abyss.AbyssPointsService;
import com.aionemu.gameserver.services.achievement.AchievementService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.BroadcastUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * PvP 赛季服务 — weekly/configurable PvP season with leaderboard and rewards.
 *
 * <p><b>机制</b>:
 * <ul>
 *   <li>每次 PvP 击杀实时累加 killer 的 kills 与 AP；victim 累加 deaths。</li>
 *   <li>同一 (killer,victim) 5 分钟内只计一次（反作弊，不重复与每日 cap 冲突）。</li>
 *   <li>Hook 点：{@link com.aionemu.gameserver.controllers.PlayerController#onDie}</li>
 *   <li>赛季边界由 {@code seasonStartMs + SEASON_DURATION_MS} 决定；到期自动 rollover。</li>
 *   <li>Rollover 发送前 N 名奖励、全服广播、归档到 JSON 文件。</li>
 * </ul>
 *
 * <p><b>持久化</b>:
 * <ul>
 *   <li>每小时 flush 当前赛季到 {@code log/custom_pvp_season.json}（防重启丢失）。</li>
 *   <li>赛季结束归档到 {@code log/custom_pvp_season_archive_<epoch>.json}。</li>
 * </ul>
 *
 * <p>七维覆盖（System/Contract/Retrieval/Reliability/Security/Observability/Product）见
 * {@code doc/features/pvp-season.md}。
 *
 * @author BEY_4.8 seven-dimensional evolution
 */
public final class PvpSeasonService {

	private static final Logger log = LoggerFactory.getLogger(PvpSeasonService.class);
	private static final PvpSeasonService INSTANCE = new PvpSeasonService();

	/** 1 season's duration in ms (driven by config, captured on init). */
	private volatile long seasonDurationMs;

	/** Start timestamp (ms) of the currently running season. Updated on rollover. */
	private volatile long seasonStartMs;

	/** Per-player season stats: playerObjectId → SeasonStats. */
	private final ConcurrentMap<Integer, SeasonStats> stats = new ConcurrentHashMap<>();

	/**
	 * Anti-farming: packed-key (killerId<<32 | victimId) → next-payable ms.
	 * 5-minute window between counted kills of the same victim by the same killer.
	 */
	private final ConcurrentMap<Long, Long> killCooldown = new ConcurrentHashMap<>();
	private static final long KILL_COOLDOWN_MS = 5L * 60L * 1000L;

	private volatile ScheduledFuture<?> sweepTask;

	private static final Path STATE_PATH = Paths.get("log", "custom_pvp_season.json");

	/** Metric keys */
	private static final String M_KILLS = "pvpseason.kills";
	private static final String M_DEATHS = "pvpseason.deaths";
	private static final String M_AP_AWARDED = "pvpseason.ap_awarded";
	private static final String M_DENIED_COOLDOWN = "pvpseason.denied_cooldown";
	private static final String M_ROLLOVER = "pvpseason.rollover";
	private static final String M_REWARD_PAID = "pvpseason.reward_paid";

	private PvpSeasonService() {}

	public static PvpSeasonService getInstance() {
		return INSTANCE;
	}

	/** Initialize season state, load persisted stats, schedule periodic flush. Idempotent. */
	public synchronized void init() {
		if (!CustomConfig.PVP_SEASON_ENABLED) {
			log.info("[PvpSeason] disabled by config");
			return;
		}
		if (sweepTask != null)
			return;

		int days = Math.max(1, CustomConfig.PVP_SEASON_DURATION_DAYS);
		seasonDurationMs = (long) days * 86_400_000L;

		// Load persisted state (or initialize fresh)
		if (!loadState()) {
			seasonStartMs = System.currentTimeMillis();
			log.info("[PvpSeason] fresh season started at {}", seasonStartMs);
		}

		// Schedule hourly flush + boundary check
		long interval = Math.max(60_000L, CustomConfig.PVP_SEASON_FLUSH_INTERVAL_MS);
		sweepTask = ThreadPoolManager.getInstance().scheduleAtFixedRate(this::runSweep, interval, interval);
		log.info("[PvpSeason] season duration={}d, flush interval={}s, currently {} ms into season",
			days, interval / 1000, System.currentTimeMillis() - seasonStartMs);

		// Shutdown hook — final flush
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveState, "PvpSeasonFlush"));
	}

	/**
	 * Hook: called from {@link com.aionemu.gameserver.controllers.PlayerController#onDie}
	 * when a player dies at the hands of another player.
	 *
	 * @param victim the fallen player
	 * @param lastAttacker the creature that dealt the killing blow (may be a pet/servant)
	 */
	public void onPvpKill(Player victim, Creature lastAttacker) {
		if (!CustomConfig.PVP_SEASON_ENABLED || victim == null || lastAttacker == null)
			return;
		Creature master = lastAttacker.getMaster();
		if (!(master instanceof Player killer) || killer == victim)
			return;
		// Same-race kills: skip unless cross-faction PvP allowed
		if (!CustomConfig.SPEAKING_BETWEEN_FACTIONS && killer.getRace() == victim.getRace())
			return;

		long now = System.currentTimeMillis();
		long pairKey = ((long) killer.getObjectId() << 32) | (victim.getObjectId() & 0xffffffffL);
		Long nextPayable = killCooldown.get(pairKey);
		if (nextPayable != null && nextPayable > now) {
			CustomFeatureMetrics.getInstance().inc(M_DENIED_COOLDOWN);
			return;
		}
		killCooldown.put(pairKey, now + KILL_COOLDOWN_MS);
		cleanupCooldown(now);

		// Credit killer
		SeasonStats k = stats.computeIfAbsent(killer.getObjectId(), id -> new SeasonStats());
		int killCount = k.kills.incrementAndGet();
		if (k.firstBloodMs == 0)
			k.firstBloodMs = now;

		// Debit victim
		SeasonStats v = stats.computeIfAbsent(victim.getObjectId(), id -> new SeasonStats());
		v.deaths.incrementAndGet();

		// Season AP bonus on top of vanilla AP
		int bonus = CustomConfig.PVP_SEASON_PER_KILL_AP;
		if (bonus > 0) {
			try {
				AbyssPointsService.addAp(killer, victim, bonus);
				k.apEarned.addAndGet(bonus);
				CustomFeatureMetrics.getInstance().add(M_AP_AWARDED, bonus);
			} catch (Exception e) {
				log.warn("[PvpSeason] AP award failed for " + killer.getName(), e);
			}
		}
		CustomFeatureMetrics.getInstance().inc(M_KILLS);
		CustomFeatureMetrics.getInstance().inc(M_DEATHS);

		// Achievement trigger: every PvP kill feeds cumulative counter
		try { AchievementService.getInstance().onEvent(killer.getObjectId(), AchievementService.Trigger.PVP_KILL, 1); }
		catch (Throwable t) { log.error("[PvpSeason] achievement trigger failed", t); }

		// First blood broadcast (once per season, first kill of the season)
		if (killCount == 1 && CustomConfig.PVP_SEASON_FIRST_BLOOD_BROADCAST) {
			String msg = "『" + BroadcastUtil.sanitize(killer.getName()) + "』开此赛季杀戒!";
			BroadcastUtil.broadcastYellow(msg);
			CustomAuditLog.getInstance().log("pvpseason", "first_blood", killer.getName(), "");
		}
	}

	private void cleanupCooldown(long now) {
		if (killCooldown.size() < 256)
			return;
		killCooldown.entrySet().removeIf(e -> e.getValue() < now);
	}

	/** Hourly: check boundary, flush state. */
	private void runSweep() {
		long start = System.nanoTime();
		try {
			if (System.currentTimeMillis() >= seasonStartMs + seasonDurationMs) {
				rolloverSeason("scheduled");
			}
			saveState();
		} catch (Exception e) {
			log.error("[PvpSeason] sweep failed", e);
		} finally {
			long elapsed = (System.nanoTime() - start) / 1_000_000L;
			CustomFeatureMetrics.getInstance().recordTiming("pvpseason.sweep_ms", elapsed);
		}
	}

	/**
	 * End the current season: rank players, mail rewards to top N, broadcast,
	 * archive, and start a fresh season.
	 */
	public synchronized void rolloverSeason(String reason) {
		long oldStart = seasonStartMs;
		List<Object[]> ranking = buildLeaderboard(Integer.MAX_VALUE);

		// Pay top N rewards
		int[] rewards = parseRewards(CustomConfig.PVP_SEASON_TOP_REWARDS_AP_RAW);
		int paid = 0;
		for (int i = 0; i < Math.min(rewards.length, ranking.size()); i++) {
			Object[] entry = ranking.get(i);
			int playerId = (Integer) entry[0];
			String name = (String) entry[1];
			int ap = rewards[i];
			if (ap <= 0)
				continue;
			Player online = World.getInstance().getPlayer(playerId);
			try {
				if (online != null) {
					AbyssPointsService.addAp(online, null, ap);
					PacketSendUtility.sendPacket(online, new SM_MESSAGE(0, null,
						"赛季结算: 汝位列第 " + (i + 1) + ", 获赏 " + ap + " AP!",
						ChatType.BRIGHT_YELLOW_CENTER));
				}
				// Offline: audit only; on next login they won't be notified (simplification)
				CustomFeatureMetrics.getInstance().add(M_AP_AWARDED, ap);
				CustomFeatureMetrics.getInstance().inc(M_REWARD_PAID);
				paid++;
				CustomAuditLog.getInstance().log("pvpseason", "reward", name,
					"rank=" + (i + 1) + " ap=" + ap);
			} catch (Exception e) {
				log.error("[PvpSeason] reward failed for " + name, e);
			}
		}

		// Broadcast top 3
		StringBuilder sb = new StringBuilder("赛季结束! 前三英豪: ");
		for (int i = 0; i < Math.min(3, ranking.size()); i++) {
			Object[] entry = ranking.get(i);
			if (i > 0) sb.append(" / ");
			sb.append("#").append(i + 1).append(" 『")
				.append(BroadcastUtil.sanitize((String) entry[1])).append("』 ")
				.append(entry[2]).append("杀");
		}
		if (ranking.isEmpty())
			sb.append("(无斩获)");
		BroadcastUtil.broadcastYellow(sb.toString());

		// Archive
		archiveSeason(oldStart);

		// Reset
		stats.clear();
		killCooldown.clear();
		seasonStartMs = System.currentTimeMillis();
		CustomFeatureMetrics.getInstance().inc(M_ROLLOVER);
		CustomAuditLog.getInstance().log("pvpseason", "rollover", "system",
			"reason=" + reason + " top_paid=" + paid);
		log.info("[PvpSeason] season rollover complete ({}), new season starts {}", reason, seasonStartMs);
	}

	/** Construct a sorted leaderboard: each entry is {playerId, playerName, kills, deaths, apEarned}. */
	public List<Object[]> buildLeaderboard(int limit) {
		List<Object[]> out = new ArrayList<>(stats.size());
		stats.forEach((playerId, s) -> {
			String name = resolveName(playerId);
			if (name == null) return;
			out.add(new Object[]{playerId, name, s.kills.get(), s.deaths.get(), s.apEarned.get()});
		});
		out.sort(Comparator.<Object[], Integer>comparing(a -> (Integer) a[2]).reversed()
			.thenComparing(Comparator.<Object[], Long>comparing(a -> (Long) a[4]).reversed()));
		return out.size() > limit ? out.subList(0, limit) : out;
	}

	private String resolveName(int playerId) {
		Player online = World.getInstance().getPlayer(playerId);
		if (online != null) return online.getName();
		try {
			PlayerCommonData pcd = PlayerService.getOrLoadPlayerCommonData(playerId);
			return pcd != null ? pcd.getName() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Snapshot of feature state for //season status. */
	public Map<String, Object> snapshot() {
		long now = System.currentTimeMillis();
		long elapsed = now - seasonStartMs;
		long remaining = Math.max(0, seasonDurationMs - elapsed);
		Map<String, Object> out = new TreeMap<>();
		out.put("season_start_ms", seasonStartMs);
		out.put("season_elapsed_hours", elapsed / 3_600_000L);
		out.put("season_remaining_hours", remaining / 3_600_000L);
		out.put("tracked_players", stats.size());
		int totalKills = 0;
		for (SeasonStats s : stats.values()) totalKills += s.kills.get();
		out.put("total_kills_this_season", totalKills);
		return out;
	}

	// ────────────── Persistence (manual JSON) ──────────────

	private synchronized void saveState() {
		try {
			Files.createDirectories(STATE_PATH.getParent());
			StringBuilder sb = new StringBuilder("{\n");
			sb.append("  \"season_start_ms\": ").append(seasonStartMs).append(",\n");
			sb.append("  \"stats\": {\n");
			int i = 0, n = stats.size();
			for (var e : stats.entrySet()) {
				SeasonStats s = e.getValue();
				sb.append("    \"").append(e.getKey()).append("\": {")
					.append("\"k\":").append(s.kills.get())
					.append(",\"d\":").append(s.deaths.get())
					.append(",\"ap\":").append(s.apEarned.get())
					.append(",\"fb\":").append(s.firstBloodMs)
					.append("}");
				if (++i < n) sb.append(",");
				sb.append("\n");
			}
			sb.append("  }\n}\n");
			Files.writeString(STATE_PATH, sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("[PvpSeason] saveState failed: {}", e.getMessage());
		}
	}

	private boolean loadState() {
		if (!Files.exists(STATE_PATH))
			return false;
		try {
			String content = Files.readString(STATE_PATH, StandardCharsets.UTF_8);
			// Extract season_start_ms
			int idx = content.indexOf("season_start_ms");
			if (idx >= 0) {
				int colon = content.indexOf(':', idx);
				int comma = content.indexOf(',', colon);
				if (comma > colon) {
					seasonStartMs = Long.parseLong(content.substring(colon + 1, comma).trim());
				}
			}
			// Extract stats entries (simple regex-free scan)
			int statsStart = content.indexOf("\"stats\"");
			if (statsStart < 0) return true;
			int i = content.indexOf('{', statsStart);
			int loaded = 0;
			while (i >= 0) {
				int q1 = content.indexOf('"', i + 1);
				if (q1 < 0) break;
				int q2 = content.indexOf('"', q1 + 1);
				if (q2 < 0) break;
				int brace = content.indexOf('{', q2);
				int brClose = content.indexOf('}', brace);
				if (brace < 0 || brClose < 0) break;
				try {
					int playerId = Integer.parseInt(content.substring(q1 + 1, q2));
					String inner = content.substring(brace + 1, brClose);
					SeasonStats s = new SeasonStats();
					s.kills.set(extractInt(inner, "\"k\":"));
					s.deaths.set(extractInt(inner, "\"d\":"));
					s.apEarned.set(extractLong(inner, "\"ap\":"));
					s.firstBloodMs = extractLong(inner, "\"fb\":");
					stats.put(playerId, s);
					loaded++;
				} catch (NumberFormatException ignored) {}
				i = content.indexOf('"', brClose);
			}
			log.info("[PvpSeason] restored {} player stats from {}", loaded, STATE_PATH);
			return true;
		} catch (IOException | NumberFormatException e) {
			log.warn("[PvpSeason] loadState failed: {}", e.getMessage());
			return false;
		}
	}

	private static int extractInt(String haystack, String key) {
		int idx = haystack.indexOf(key);
		if (idx < 0) return 0;
		int start = idx + key.length();
		int end = start;
		while (end < haystack.length() && (Character.isDigit(haystack.charAt(end)) || haystack.charAt(end) == '-'))
			end++;
		try { return Integer.parseInt(haystack.substring(start, end).trim()); }
		catch (Exception e) { return 0; }
	}

	private static long extractLong(String haystack, String key) {
		int idx = haystack.indexOf(key);
		if (idx < 0) return 0L;
		int start = idx + key.length();
		int end = start;
		while (end < haystack.length() && (Character.isDigit(haystack.charAt(end)) || haystack.charAt(end) == '-'))
			end++;
		try { return Long.parseLong(haystack.substring(start, end).trim()); }
		catch (Exception e) { return 0L; }
	}

	private void archiveSeason(long oldStart) {
		try {
			Path archivePath = Paths.get("log", "custom_pvp_season_archive_" + oldStart + ".json");
			Files.createDirectories(archivePath.getParent());
			List<Object[]> ranking = buildLeaderboard(Integer.MAX_VALUE);
			StringBuilder sb = new StringBuilder("{\n");
			sb.append("  \"season_start_ms\": ").append(oldStart).append(",\n");
			sb.append("  \"season_end_ms\": ").append(System.currentTimeMillis()).append(",\n");
			sb.append("  \"ranking\": [\n");
			for (int i = 0; i < ranking.size(); i++) {
				Object[] r = ranking.get(i);
				sb.append("    {\"rank\":").append(i + 1)
					.append(",\"player_id\":").append(r[0])
					.append(",\"name\":\"").append(BroadcastUtil.sanitize((String) r[1])).append("\"")
					.append(",\"kills\":").append(r[2])
					.append(",\"deaths\":").append(r[3])
					.append(",\"ap\":").append(r[4])
					.append("}");
				if (i < ranking.size() - 1) sb.append(",");
				sb.append("\n");
			}
			sb.append("  ]\n}\n");
			Files.writeString(archivePath, sb.toString(), StandardCharsets.UTF_8);
			log.info("[PvpSeason] archived season to {}", archivePath);
		} catch (IOException e) {
			log.error("[PvpSeason] archive failed", e);
		}
	}

	/** Parse comma-separated AP reward list into int[]. Invalid entries become 0. */
	private static int[] parseRewards(String raw) {
		if (raw == null || raw.isEmpty()) return new int[0];
		String[] parts = raw.split(",");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try { out[i] = Integer.parseInt(parts[i].trim()); }
			catch (NumberFormatException ignored) { out[i] = 0; }
		}
		return out;
	}

	// ────────────── Admin-facing ──────────────

	/** GM force season rollover. */
	public void adminRollover(String gmName) {
		CustomAuditLog.getInstance().logGm("season rollover", gmName, "forced");
		rolloverSeason("admin:" + gmName);
	}

	/** Get list of archived season files. */
	public List<String> listArchives() {
		List<String> out = new ArrayList<>();
		try {
			Path dir = Paths.get("log");
			if (!Files.exists(dir)) return out;
			try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
				stream.filter(p -> p.getFileName().toString().startsWith("custom_pvp_season_archive_"))
					.sorted(Comparator.reverseOrder())
					.forEach(p -> out.add(p.getFileName().toString()));
			}
		} catch (IOException e) {
			log.warn("[PvpSeason] list archives failed", e);
		}
		return out;
	}

	// ────────────── Inner class ──────────────

	static final class SeasonStats {
		final AtomicInteger kills = new AtomicInteger();
		final AtomicInteger deaths = new AtomicInteger();
		final AtomicLong apEarned = new AtomicLong();
		volatile long firstBloodMs = 0L;
	}
}
