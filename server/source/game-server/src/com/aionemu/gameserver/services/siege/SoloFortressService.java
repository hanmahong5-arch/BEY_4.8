package com.aionemu.gameserver.services.siege;

import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.dao.SiegeDAO;
import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.services.achievement.AchievementService;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.LetterType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.siege.FortressLocation;
import com.aionemu.gameserver.model.siege.SiegeLocation;
import com.aionemu.gameserver.model.siege.SiegeRace;
import com.aionemu.gameserver.model.templates.siegelocation.SiegeLocationTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.services.SiegeService;
import com.aionemu.gameserver.services.abyss.AbyssPointsService;
import com.aionemu.gameserver.services.mail.SystemMailService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.utils.BroadcastUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * 单人要塞服务 — single-player fortress ownership + supporting depth mechanics.
 *
 * <p><b>职能</b>:
 * <ul>
 *   <li>{@link #onSoloCapture} — called from {@link FortressSiege#onCapture} when
 *       the top-damage participant is (or should be) a lone player. Flips the
 *       fortress ownership fields and broadcasts the coronation worldwide.</li>
 *   <li>{@link #applyLordBuff} / {@link #removeLordBuff} — called from
 *       {@link SiegeLocation#onEnterZone}/{@code onLeaveZone} to grant the
 *       configured prestige skill while the lord is physically inside their
 *       fortress's zone.</li>
 *   <li>Background sweep (every {@link CustomConfig#SOLO_FORTRESS_SWEEP_INTERVAL_MS}):
 *     <ol>
 *       <li>Decay check — if the lord has been offline for more than
 *           {@link CustomConfig#SOLO_FORTRESS_DECAY_DAYS} days, the fortress
 *           auto-reverts to Balaur (anti-squatting).</li>
 *       <li>Tax payment — mails the lord kinah scaled by fortress tier
 *           (occupy_count) as a passive income stream.</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>All state lives on {@link SiegeLocation} (ownerPlayerId, ownerCapturedAt);
 * this service is purely stateless orchestration.
 *
 * @author BEY_4.8 Solo Fortress feature
 */
public final class SoloFortressService {

	private static final Logger log = LoggerFactory.getLogger(SoloFortressService.class);

	private static final SoloFortressService INSTANCE = new SoloFortressService();

	/**
	 * Anti-farming cooldown for fortress-lord bounty AP. Key is packed as
	 * {@code ((long)killerObjId << 32) | (victimObjId & 0xffffffffL)}; value is the
	 * wall-clock ms at which the next bounty from killer→victim is payable.
	 * Prevents two colluding accounts from milking AP by trading deaths.
	 */
	private final ConcurrentMap<Long, Long> bountyCooldown = new ConcurrentHashMap<>();

	/** Default cooldown window per killer/victim pair. 15 minutes. */
	private static final long BOUNTY_COOLDOWN_MS = 15L * 60L * 1000L;

	/** Metric keys — keep as constants so the admin command glossary stays in sync. */
	private static final String M_CAPTURE        = "fortress.solo.capture";
	private static final String M_DETHRONE       = "fortress.solo.dethrone";
	private static final String M_BOUNTY_AWARDED = "fortress.solo.bounty.awarded";
	private static final String M_BOUNTY_AP      = "fortress.solo.bounty.ap_total";
	private static final String M_BOUNTY_DENIED  = "fortress.solo.bounty.denied_cooldown";
	private static final String M_TAX_PAID       = "fortress.solo.tax.paid";
	private static final String M_TAX_KINAH      = "fortress.solo.tax.kinah_total";
	private static final String M_DECAY          = "fortress.solo.decay";
	private static final String M_LORD_BUFF      = "fortress.solo.lord_buff.applied";

	private volatile ScheduledFuture<?> sweepTask;

	private SoloFortressService() {}

	public static SoloFortressService getInstance() {
		return INSTANCE;
	}

	private static final java.nio.file.Path METRICS_SAVE_PATH = Paths.get("log", "custom_metrics_snapshot.json");

	/** Start the background sweep (tax + decay). Also bootstraps metrics persistence. Idempotent. */
	public synchronized void init() {
		// Restore counters from previous session (Reliability dimension)
		CustomFeatureMetrics.getInstance().loadFromFile(METRICS_SAVE_PATH);
		CustomFeatureMetrics.getInstance().registerShutdownPersistence(METRICS_SAVE_PATH);

		if (!CustomConfig.SOLO_FORTRESS_ENABLED) {
			log.info("[SoloFortress] disabled by config; service not initialized");
			return;
		}
		if (sweepTask != null)
			return;
		long interval = CustomConfig.SOLO_FORTRESS_SWEEP_INTERVAL_MS;
		if (interval <= 0) {
			log.warn("[SoloFortress] sweep interval <= 0, refusing to start");
			return;
		}
		sweepTask = ThreadPoolManager.getInstance().scheduleAtFixedRate(this::runSweep, interval, interval);
		log.info("[SoloFortress] sweep scheduled every " + (interval / 1000) + "s (tax + decay)");
	}

	/**
	 * Commit solo ownership to the given location and broadcast the coronation.
	 * Must be called <b>after</b> {@code setRace} but <b>before</b> {@code updateSiegeLocation}.
	 * Forces legion_id to 0 so the vanilla legion reward path is cleanly bypassed.
	 *
	 * <p>Defensive against null template/name: the coronation will still persist
	 * and broadcast even if the template layer is partially broken.
	 */
	public void onSoloCapture(FortressLocation loc, int playerObjId, String playerName) {
		if (loc == null || playerObjId == 0)
			return;
		loc.setOwnerPlayerId(playerObjId);
		loc.setOwnerCapturedAt(System.currentTimeMillis());
		loc.setLegionId(0);

		String fortressName = safeFortressName(loc);
		String safeName = playerName == null ? "#" + playerObjId : BroadcastUtil.sanitize(playerName);

		// Count how many fortresses this player now holds (for rank title)
		int ownedCount = countFortressesOwned(playerObjId);
		String titled = BroadcastUtil.lordTitle(safeName, ownedCount);
		String msg = "『" + titled + "』已染指 <" + fortressName + "> 之王座!!";
		BroadcastUtil.broadcastYellow(msg);
		CustomFeatureMetrics.getInstance().inc(M_CAPTURE);
		CustomAuditLog.getInstance().log("fortress", "capture", safeName,
			"fortress=" + loc.getLocationId() + " owned=" + ownedCount);
		// Achievement triggers
		try {
			AchievementService.getInstance().onEvent(playerObjId, AchievementService.Trigger.FORTRESS_CAPTURE, 1);
			AchievementService.getInstance().onEvent(playerObjId, AchievementService.Trigger.FORTRESS_CONCURRENT, ownedCount);
		} catch (Throwable t) { log.error("[SoloFortress] achievement trigger failed", t); }
		log.info("[SoloFortress] {} ({}) crowned as lord of fortress {} (id={}), rank={}", safeName, playerObjId, fortressName, loc.getLocationId(), BroadcastUtil.lordRank(ownedCount));
	}

	/**
	 * Apply the configured lord buff to the player iff they are the current lord
	 * of the given fortress. Called from {@link SiegeLocation#onEnterZone}.
	 *
	 * <p>Zone-enter de-duplication is already enforced by
	 * {@link SiegeLocation#onEnterZone}'s {@code containsKey} guard, so this
	 * method is called at most once per lord per fortress-entry.
	 */
	public void applyLordBuff(Player player, SiegeLocation loc) {
		if (player == null || loc == null)
			return;
		if (loc.getOwnerPlayerId() == 0 || player.getObjectId() != loc.getOwnerPlayerId())
			return;
		int skillId = CustomConfig.SOLO_FORTRESS_LORD_BUFF_SKILL_ID;
		if (skillId <= 0)
			return;
		try {
			SkillEngine.getInstance().applyEffectDirectly(skillId, player, player);
			CustomFeatureMetrics.getInstance().inc(M_LORD_BUFF);
		} catch (Exception e) {
			log.warn("[SoloFortress] applyLordBuff failed for " + player.getName(), e);
		}
	}

	/** Remove the lord buff on zone leave. No-op if skill id is unset or player already gone. */
	public void removeLordBuff(Player player, SiegeLocation loc) {
		if (player == null)
			return;
		int skillId = CustomConfig.SOLO_FORTRESS_LORD_BUFF_SKILL_ID;
		if (skillId <= 0)
			return;
		try {
			player.getEffectController().removeEffect(skillId);
		} catch (Exception e) {
			log.warn("[SoloFortress] removeLordBuff failed for " + player.getName(), e);
		}
	}

	/**
	 * Called from {@link com.aionemu.gameserver.controllers.PlayerController#onDie}
	 * to award a bounty when a fortress lord is slain. The bounty scales with how
	 * many fortresses the victim holds — more prestigious targets pay out more.
	 *
	 * <p>No-op if the victim owns nothing, the killer is the victim themselves,
	 * the killer is not a player (NPC kills don't award AP), the feature is
	 * disabled, or the same (killer,victim) pair has collected a bounty within
	 * the last {@link #BOUNTY_COOLDOWN_MS}. The victim's ownership is NOT revoked
	 * by this call — they stay lord through death, losing only if their fortress
	 * is re-sieged.
	 *
	 * <p><b>Anti-farming</b>: two players (or an alt pair) cannot cycle deaths to
	 * mill AP. The cooldown map is stamped per-pair; stale entries are cleaned up
	 * lazily on the next insert.
	 */
	public void onLordKilled(Player victim, Creature lastAttacker) {
		if (!CustomConfig.SOLO_FORTRESS_ENABLED || victim == null || lastAttacker == null)
			return;
		Creature master = lastAttacker.getMaster();
		if (!(master instanceof Player killer) || killer == victim)
			return;

		// Tally fortresses held by the victim.
		int ownedCount = 0;
		StringBuilder names = new StringBuilder();
		Collection<FortressLocation> fortresses = safeGetFortresses();
		for (FortressLocation f : fortresses) {
			if (f != null && f.getOwnerPlayerId() == victim.getObjectId()) {
				ownedCount++;
				if (names.length() > 0)
					names.append('/');
				names.append(safeFortressName(f));
			}
		}
		if (ownedCount == 0)
			return;

		// Cooldown gate: reject repeat bounties from the same killer on the same victim.
		long now = System.currentTimeMillis();
		long pairKey = ((long) killer.getObjectId() << 32) | (victim.getObjectId() & 0xffffffffL);
		Long nextPayable = bountyCooldown.get(pairKey);
		if (nextPayable != null && nextPayable > now) {
			long remaining = (nextPayable - now) / 1000;
			CustomFeatureMetrics.getInstance().inc(M_BOUNTY_DENIED);
			PacketSendUtility.sendPacket(killer,
				new SM_MESSAGE(0, null, "悬赏冷却中, 对此领主 " + remaining + " 秒内不再奖赏。", ChatType.BRIGHT_YELLOW_CENTER));
			log.info("[SoloFortress] bounty denied (cooldown): {} -> {} remaining {}s", killer.getName(), victim.getName(), remaining);
			return;
		}
		bountyCooldown.put(pairKey, now + BOUNTY_COOLDOWN_MS);
		cleanupBountyCooldown(now);

		// Award AP bounty.
		int bounty = Math.max(0, CustomConfig.SOLO_FORTRESS_BOUNTY_AP * ownedCount);
		if (bounty > 0) {
			try {
				AbyssPointsService.addAp(killer, victim, bounty);
				CustomFeatureMetrics.getInstance().add(M_BOUNTY_AP, bounty);
			} catch (Exception e) {
				log.warn("[SoloFortress] failed to award bounty to " + killer.getName(), e);
			}
		}
		CustomFeatureMetrics.getInstance().inc(M_BOUNTY_AWARDED);

		String safeKiller = BroadcastUtil.sanitize(killer.getName());
		String safeVictim = BroadcastUtil.sanitize(victim.getName());
		String victimTitled = BroadcastUtil.lordTitle(safeVictim, ownedCount);
		PacketSendUtility.sendPacket(killer,
			new SM_MESSAGE(0, null, "悬赏兑现: 斩杀领主 " + victimTitled + ", 获赏 " + bounty + " AP!", ChatType.BRIGHT_YELLOW_CENTER));
		PacketSendUtility.sendPacket(victim,
			new SM_MESSAGE(0, null, "汝已为 " + safeKiller + " 所弑, 然王座犹在。", ChatType.BRIGHT_YELLOW_CENTER));

		String broadcastMsg = "『" + safeKiller + "』已取 <" + victimTitled + "> (" + names + " 之主) 首级!";
		World.getInstance().forEachPlayer(p -> {
			if (p != killer && p != victim)
				PacketSendUtility.sendPacket(p, new SM_MESSAGE(0, null, broadcastMsg, ChatType.BRIGHT_YELLOW_CENTER));
		});
		CustomAuditLog.getInstance().log("fortress", "bounty", safeKiller,
			"victim=" + safeVictim + " ap=" + bounty + " fortresses=" + ownedCount);
		// Achievement trigger: each lord kill feeds cumulative counter
		try { AchievementService.getInstance().onEvent(killer.getObjectId(), AchievementService.Trigger.LORD_KILL, 1); }
		catch (Throwable t) { log.error("[SoloFortress] achievement trigger failed", t); }
		log.info("[SoloFortress] bounty {} AP: {} killed lord {} (owned {})", bounty, safeKiller, safeVictim, ownedCount);
	}

	/** Evict bounty cooldown entries older than the cooldown window. O(N) but N is bounded by active PvP pairs. */
	private void cleanupBountyCooldown(long now) {
		if (bountyCooldown.size() < 64)
			return; // hot-path micro-optimization: ignore until the map grows meaningful
		bountyCooldown.entrySet().removeIf(e -> e.getValue() < now);
	}

	/**
	 * Called from {@link FortressSiege#onCapture} when a fresh siege overthrows
	 * an existing solo lord. Broadcasts the fall and notifies the old lord.
	 * The caller is responsible for subsequently clearing the ownership fields.
	 *
	 * @param loc the fortress whose lord is being dethroned
	 * @param newOwnerName the display name of the incoming owner (lord or race)
	 */
	public void onLordDethroned(SiegeLocation loc, String newOwnerName) {
		if (loc == null)
			return;
		int oldOwnerId = loc.getOwnerPlayerId();
		if (oldOwnerId == 0)
			return;
		PlayerCommonData pcd = null;
		try {
			pcd = PlayerService.getOrLoadPlayerCommonData(oldOwnerId);
		} catch (Exception e) {
			log.warn("[SoloFortress] PCD lookup failed for #" + oldOwnerId, e);
		}
		String oldName = pcd != null ? pcd.getName() : ("#" + oldOwnerId);
		String fortressName = safeFortressName(loc);
		String safeNewOwner = newOwnerName == null ? "?" : newOwnerName;

		String broadcastMsg = "『" + BroadcastUtil.sanitize(oldName) + "』失 <" + fortressName + "> 之治, 已为 " + safeNewOwner + " 所取而代之。";
		BroadcastUtil.broadcastYellow(broadcastMsg);
		CustomAuditLog.getInstance().log("fortress", "dethrone", oldName,
			"fortress=" + loc.getLocationId() + " new_owner=" + safeNewOwner);

		Player oldLord = World.getInstance().getPlayer(oldOwnerId);
		if (oldLord != null) {
			String personal = "<" + fortressName + "> 已不在汝治下。";
			PacketSendUtility.sendPacket(oldLord, new SM_MESSAGE(0, null, personal, ChatType.BRIGHT_YELLOW_CENTER));
		}
		CustomFeatureMetrics.getInstance().inc(M_DETHRONE);
		log.info("[SoloFortress] dethroned {} from fortress {} -> {}", oldName, loc.getLocationId(), safeNewOwner);
	}

	/**
	 * Called from {@code PlayerEnterWorldService.enterWorld} at the end of login.
	 * If the player currently rules at least one fortress, greet them with a
	 * summary: count, names, and a nudge to defend. Lords get social feedback.
	 */
	public void onPlayerLogin(Player player) {
		if (!CustomConfig.SOLO_FORTRESS_ENABLED || player == null)
			return;
		int count = 0;
		StringBuilder names = new StringBuilder();
		for (FortressLocation f : safeGetFortresses()) {
			if (f != null && f.getOwnerPlayerId() == player.getObjectId()) {
				count++;
				if (names.length() > 0)
					names.append(" / ");
				names.append(safeFortressName(f));
			}
		}
		if (count == 0)
			return;
		String rank = BroadcastUtil.lordRank(count);
		String greet = "欢迎归位, " + (rank.isEmpty() ? "" : rank + " — ") + count + " 座要塞之主:『" + names + "』— 王冠愈重, 敌手愈众。";
		PacketSendUtility.sendPacket(player, new SM_MESSAGE(0, null, greet, ChatType.BRIGHT_YELLOW_CENTER));
	}

	/** Background task — runs tax + decay for every fortress every N ms. Timed for observability. */
	private void runSweep() {
		long start = System.nanoTime();
		try {
			for (FortressLocation f : safeGetFortresses()) {
				if (f == null || f.getOwnerPlayerId() == 0)
					continue;
				try {
					boolean decayed = checkDecay(f);
					if (decayed)
						continue;
					sendHourlyTax(f);
				} catch (Exception innerEx) {
					log.error("[SoloFortress] sweep failed for fortress " + f.getLocationId(), innerEx);
				}
			}
		} catch (Exception e) {
			log.error("[SoloFortress] sweep task failed", e);
		} finally {
			long elapsed = (System.nanoTime() - start) / 1_000_000L;
			CustomFeatureMetrics.getInstance().recordTiming("fortress.solo.sweep_ms", elapsed);
		}
	}

	/**
	 * Strip ownership if the lord has been offline longer than the decay threshold.
	 * Online lords never decay (lastOnline stamp only refreshes on logout).
	 *
	 * @return true if the fortress was decayed (caller should skip tax payout).
	 */
	private boolean checkDecay(FortressLocation loc) {
		int days = CustomConfig.SOLO_FORTRESS_DECAY_DAYS;
		if (days <= 0)
			return false;
		int ownerId = loc.getOwnerPlayerId();
		// Online lord → active, never decay.
		if (World.getInstance().getPlayer(ownerId) != null)
			return false;
		PlayerCommonData pcd;
		try {
			pcd = PlayerService.getOrLoadPlayerCommonData(ownerId);
		} catch (Exception e) {
			log.error("[SoloFortress] PCD load failed for decay check of #" + ownerId, e);
			return false; // skip this sweep tick; retry next hour
		}
		if (pcd == null) {
			// Unknown player (deleted account?) — reset defensively.
			resetOwnership(loc, "unknown player id " + ownerId);
			return true;
		}
		Timestamp last = pcd.getLastOnline();
		if (last == null)
			return false;
		long ageMs = System.currentTimeMillis() - last.getTime();
		long thresholdMs = days * 86_400_000L;
		if (ageMs < thresholdMs)
			return false;
		log.info("[SoloFortress] decay: {} inactive {}d → fortress {} reset", pcd.getName(), ageMs / 86_400_000L, loc.getLocationId());
		resetOwnership(loc, pcd.getName() + " inactive " + (ageMs / 86_400_000L) + "d");
		String msg = "『" + BroadcastUtil.sanitize(pcd.getName()) + "』疏于经营, 要塞 <" + safeFortressName(loc) + "> 已归 Balaur.";
		BroadcastUtil.broadcastYellow(msg);
		return true;
	}

	/**
	 * Zero out ownership fields and drop the fortress to Balaur. Persists immediately.
	 * In-memory mutation happens first; if DB persistence fails, the in-memory state
	 * is kept (next sweep tick will retry) — better stale-in-RAM than divergent-on-disk.
	 */
	private void resetOwnership(FortressLocation loc, String reason) {
		loc.setOwnerPlayerId(0);
		loc.setOwnerCapturedAt(0);
		loc.setLegionId(0);
		loc.setRace(SiegeRace.BALAUR);
		boolean persisted = false;
		try {
			persisted = SiegeDAO.updateSiegeLocation(loc);
		} catch (Exception e) {
			log.error("[SoloFortress] DB persist failed during reset of fortress " + loc.getLocationId(), e);
		}
		CustomFeatureMetrics.getInstance().inc(M_DECAY);
		CustomAuditLog.getInstance().log("fortress", "reset", "system", "fortress=" + loc.getLocationId() + " reason=" + reason);
		log.info("[SoloFortress] fortress {} ownership reset ({}): {}", loc.getLocationId(), persisted ? "persisted" : "IN-MEMORY ONLY", reason);
	}

	/** Mail the lord the hourly tax, scaled by fortress tier (occupy_count). */
	private void sendHourlyTax(FortressLocation loc) {
		long baseKinah = CustomConfig.SOLO_FORTRESS_HOURLY_TAX_KINAH;
		if (baseKinah <= 0)
			return;
		double tierMult = 1.0 + CustomConfig.SOLO_FORTRESS_TAX_TIER_MULT * Math.max(0, loc.getOccupiedCount() - 1);
		long kinah = Math.round(baseKinah * tierMult);
		if (kinah <= 0)
			return;

		PlayerCommonData pcd;
		try {
			pcd = PlayerService.getOrLoadPlayerCommonData(loc.getOwnerPlayerId());
		} catch (Exception e) {
			log.error("[SoloFortress] PCD load failed for tax of #" + loc.getOwnerPlayerId(), e);
			return;
		}
		if (pcd == null)
			return;

		String title = "要塞税赋";
		String body = "<" + safeFortressName(loc) + "> 之领主岁入 " + kinah + " 基纳。";
		boolean ok;
		try {
			ok = SystemMailService.sendMail("$$LORD_TAX", pcd.getName(), title, body, 0, 0, kinah, LetterType.EXPRESS);
		} catch (Exception e) {
			log.error("[SoloFortress] tax mail dispatch threw for " + pcd.getName(), e);
			return;
		}
		if (ok) {
			CustomFeatureMetrics.getInstance().inc(M_TAX_PAID);
			CustomFeatureMetrics.getInstance().add(M_TAX_KINAH, kinah);
			CustomAuditLog.getInstance().log("fortress", "tax", pcd.getName(),
				"fortress=" + loc.getLocationId() + " kinah=" + kinah);
			log.info("[SoloFortress] tax mailed: {} kinah to {} (fortress {})", kinah, pcd.getName(), loc.getLocationId());
		} else {
			log.warn("[SoloFortress] tax mail failed to {} (fortress {})", pcd.getName(), loc.getLocationId());
		}
	}

	// ────────────── Helpers ──────────────

	/** Centralized null-safe fortress display name. Falls back to "#<id>". */
	private static String safeFortressName(SiegeLocation loc) {
		if (loc == null)
			return "?";
		SiegeLocationTemplate tpl = loc.getTemplate();
		if (tpl == null)
			return "#" + loc.getLocationId();
		Object l10n = tpl.getL10n();
		return l10n == null ? "#" + loc.getLocationId() : l10n.toString();
	}

	/** Null-safe fortress view that tolerates SiegeService not being initialized yet. */
	private static Collection<FortressLocation> safeGetFortresses() {
		try {
			SiegeService svc = SiegeService.getInstance();
			if (svc == null)
				return Collections.emptyList();
			return svc.getFortresses().values();
		} catch (Exception e) {
			log.error("[SoloFortress] fortress enumeration failed", e);
			return Collections.emptyList();
		}
	}

	/** Count how many fortresses a player currently owns. */
	private int countFortressesOwned(int playerObjId) {
		int count = 0;
		for (FortressLocation f : safeGetFortresses()) {
			if (f != null && f.getOwnerPlayerId() == playerObjId)
				count++;
		}
		return count;
	}

	// ────────────── Admin-facing queries (used by //fortress command) ──────────────

	/** Force-clear solo ownership for the given fortress id. Returns true if applied. */
	public boolean adminResetFortress(int fortressId) {
		FortressLocation loc = SiegeService.getInstance().getFortress(fortressId);
		if (loc == null)
			return false;
		resetOwnership(loc, "admin command");
		BroadcastUtil.broadcastYellow("<" + safeFortressName(loc) + "> 之主权已被管理员重置。");
		return true;
	}

	/**
	 * Directly assign fortress ownership to a player (GM grant command).
	 * Returns true if applied successfully.
	 */
	public boolean adminGrantFortress(int fortressId, int playerObjId, String playerName) {
		FortressLocation loc = SiegeService.getInstance().getFortress(fortressId);
		if (loc == null)
			return false;
		loc.setOwnerPlayerId(playerObjId);
		loc.setOwnerCapturedAt(System.currentTimeMillis());
		loc.setLegionId(0);
		boolean persisted = false;
		try {
			persisted = SiegeDAO.updateSiegeLocation(loc);
		} catch (Exception e) {
			log.error("[SoloFortress] DB persist failed on admin grant for fortress " + fortressId, e);
		}
		int owned = countFortressesOwned(playerObjId);
		String titled = BroadcastUtil.lordTitle(BroadcastUtil.sanitize(playerName), owned);
		BroadcastUtil.broadcastYellow("『" + titled + "』受封为 <" + safeFortressName(loc) + "> 之主。");
		CustomFeatureMetrics.getInstance().inc(M_CAPTURE);
		CustomAuditLog.getInstance().log("fortress", "grant", playerName,
			"fortress=" + fortressId + " persisted=" + persisted);
		log.info("[SoloFortress] admin granted fortress {} to {} ({})", fortressId, playerName, playerObjId);
		return true;
	}

	/**
	 * Leaderboard: returns list of (playerName, fortressCount) sorted by count desc.
	 * Each entry is a 3-element Object[]: {String name, int count, String fortressNames}.
	 */
	public List<Object[]> getLeaderboard() {
		// Aggregate per player
		ConcurrentMap<Integer, List<String>> playerFortresses = new ConcurrentHashMap<>();
		for (FortressLocation f : safeGetFortresses()) {
			if (f == null || f.getOwnerPlayerId() == 0)
				continue;
			playerFortresses.computeIfAbsent(f.getOwnerPlayerId(), k -> new ArrayList<>())
				.add(safeFortressName(f));
		}
		List<Object[]> result = new ArrayList<>();
		for (var entry : playerFortresses.entrySet()) {
			PlayerCommonData pcd = null;
			try {
				pcd = PlayerService.getOrLoadPlayerCommonData(entry.getKey());
			} catch (Exception ignored) {}
			String name = pcd != null ? pcd.getName() : "#" + entry.getKey();
			result.add(new Object[]{name, entry.getValue().size(), String.join("/", entry.getValue())});
		}
		result.sort(Comparator.<Object[], Integer>comparing(a -> (Integer) a[1]).reversed());
		return result;
	}
}
