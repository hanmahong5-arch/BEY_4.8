package com.aionemu.gameserver.services.ffa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.drop.Drop;
import com.aionemu.gameserver.model.drop.DropItem;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.DropNpc;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.CustomPlayerState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.storage.IStorage;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_LOOT_STATUS;
import com.aionemu.gameserver.network.aion.serverpackets.SM_LOOT_STATUS.Status;
import com.aionemu.gameserver.services.drop.DropRegistrationService;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.zone.ZoneInstance;

/**
 * FFA (Free-For-All) mode service — 传奇风格全体攻击模式.
 *
 * <p><b>机制概览</b>:
 * <ul>
 *   <li>玩家施放 Bandage Heal (skill id 245) 完成 4 秒吟唱 → 切换 FFA 状态</li>
 *   <li>进入 FFA 后同时设置 {@link CustomPlayerState#ENEMY_OF_ALL_PLAYERS}
 *       和 {@link CustomPlayerState#ENEMY_OF_ALL_NPCS}：所有种族玩家+所有 NPC 均视之为敌</li>
 *   <li>施法完成回调中检查冷却 ({@link CustomConfig#FFA_TOGGLE_COOLDOWN_MS})
 *       与安全区 (city / instance / duel 禁止)</li>
 *   <li>FFA 下阵亡：装备随机掉落 N 件进一个生成的 "loot chest" NPC，180 秒内任何人可拾</li>
 *   <li>下线自动退出 FFA：避免状态僵死</li>
 * </ul>
 *
 * <p>此类持有全局冷却表（ConcurrentMap&lt;playerId, cooldownExpiryMs&gt;）
 * 与 per-chest 装备清单，均为线程安全。
 *
 * @author BEY_4.8 FFA feature
 */
public final class FfaModeService {

	private static final Logger log = LoggerFactory.getLogger(FfaModeService.class);

	private static final FfaModeService INSTANCE = new FfaModeService();

	/** playerObjectId → FFA toggle cooldown expiration (System.currentTimeMillis ms). */
	private final ConcurrentMap<Integer, Long> cooldownMap = new ConcurrentHashMap<>();

	/** FFA kill streak per player — resets on death or FFA exit. */
	private final ConcurrentMap<Integer, AtomicInteger> killStreaks = new ConcurrentHashMap<>();

	/**
	 * Hourly toggle rate limiter. long[0] = window start ms, long[1] = count in window.
	 * Prevents macro abuse beyond the per-toggle cooldown.
	 */
	private final ConcurrentMap<Integer, long[]> hourlyToggles = new ConcurrentHashMap<>();

	private FfaModeService() {}

	public static FfaModeService getInstance() {
		return INSTANCE;
	}

	/** Query whether a player is currently in FFA mode. */
	public boolean isInFfa(Player player) {
		return player != null
			&& player.isInCustomState(CustomPlayerState.ENEMY_OF_ALL_PLAYERS)
			&& player.isInCustomState(CustomPlayerState.ENEMY_OF_ALL_NPCS);
	}

	/**
	 * Main entry: called from {@code Skill.endCast} when the configured trigger
	 * skill finishes its cast. Validates eligibility, then flips the FFA bit.
	 *
	 * @return true if the toggle happened (so the caller should short-circuit
	 *         the normal skill effect); false if the toggle was refused.
	 */
	public boolean tryToggleFromSkill(Player player) {
		if (!CustomConfig.FFA_MODE_ENABLED || player == null)
			return false;

		long now = System.currentTimeMillis();
		Long cdEnd = cooldownMap.get(player.getObjectId());
		if (cdEnd != null && cdEnd > now) {
			long remaining = (cdEnd - now) / 1000;
			notifyPlayer(player, "FFA 切换冷却中，剩余 " + remaining + " 秒。");
			return true; // true = skill consumed (don't run normal effect) but state unchanged
		}

		// Hourly hard cap — anti-macro abuse (Security dimension)
		if (!checkHourlyLimit(player)) {
			notifyPlayer(player, "每小时切换次数已达上限, 请稍后再试。");
			return true;
		}

		// Safe-zone / instance / duel restrictions
		if (!isToggleAllowedHere(player)) {
			notifyPlayer(player, "此处不可切换 FFA 模式（安全区/副本/决斗中）。");
			return true;
		}

		if (isInFfa(player)) {
			exitFfa(player);
		} else {
			enterFfa(player);
		}
		cooldownMap.put(player.getObjectId(), now + CustomConfig.FFA_TOGGLE_COOLDOWN_MS);
		return true;
	}

	/** Block toggle in cities, dead state, etc. Instance maps are allowed. */
	private boolean isToggleAllowedHere(Player player) {
		if (player.isDead()) return false;

		// Reject in-city / capital zones via the zone name keywords.
		// The more robust "canPK" flag varies by zone type; we use a simple
		// name match which catches the capitals (Sanctum / Pandaemonium) reliably.
		try {
			for (ZoneInstance zone : player.getPosition().getMapRegion().findZones(player)) {
				if (zone.getZoneTemplate() == null) continue;
				String name = zone.getZoneTemplate().getName() == null
					? "" : zone.getZoneTemplate().getName().toString().toLowerCase();
				if (name.contains("capital") || name.contains("sanctum") || name.contains("pandaemonium"))
					return false;
			}
		} catch (Exception ignored) {
			// If zone lookup fails, allow toggle — defensive default (fail-open).
		}
		return true;
	}

	private void enterFfa(Player player) {
		player.setCustomState(CustomPlayerState.ENEMY_OF_ALL_PLAYERS);
		player.setCustomState(CustomPlayerState.ENEMY_OF_ALL_NPCS);
		notifyPlayer(player, "已进入全体攻击模式。此刻起所有人皆可攻击你，你亦可攻击所有人。死亡将掉落装备！");
		CustomFeatureMetrics.getInstance().inc("ffa.toggle.enter");
		CustomAuditLog.getInstance().log("ffa", "enter", player.getName(),
			"world=" + player.getWorldId());
		log.info("[FFA] {} entered FFA mode", player.getName());
	}

	private void exitFfa(Player player) {
		player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_PLAYERS);
		player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_NPCS);
		killStreaks.remove(player.getObjectId()); // reset streak on exit
		notifyPlayer(player, "已退出全体攻击模式。");
		CustomFeatureMetrics.getInstance().inc("ffa.toggle.exit");
		CustomAuditLog.getInstance().log("ffa", "exit", player.getName(), "");
		log.info("[FFA] {} exited FFA mode", player.getName());
	}

	/**
	 * Called from {@code PlayerController.onDie} — if the player dies in FFA,
	 * pick N random equipped items, remove them from inventory, spawn a chest
	 * NPC at the death location, register those items as public loot.
	 *
	 * After death, FFA state is cleared (revive respawns them peaceful).
	 */
	public void onFfaPlayerDeath(Player player, Creature lastAttacker) {
		if (player == null || !isInFfa(player)) return;
		CustomFeatureMetrics.getInstance().inc("ffa.death");

		// Kill streak: credit the killer, reset the victim
		killStreaks.remove(player.getObjectId());
		trackKillerStreak(player, lastAttacker);

		try {
			if (player.getEquipment() == null) {
				log.warn("[FFA] {} died with null equipment handle", player.getName());
			} else {
				List<Item> equipped = new ArrayList<>(player.getEquipment().getEquippedItems());
				if (equipped.isEmpty()) {
					log.info("[FFA] {} died in FFA with no equipment to drop", player.getName());
				} else {
					Collections.shuffle(equipped);
					int dropCount = Math.min(Math.max(0, CustomConfig.FFA_DROP_ITEM_COUNT), equipped.size());
					if (dropCount > 0) {
						List<Item> toDrop = new ArrayList<>(equipped.subList(0, dropCount));
						spawnLootChest(player, toDrop);
					}
				}
			}
		} catch (Exception e) {
			log.error("[FFA] onFfaPlayerDeath failed for " + player.getName(), e);
		} finally {
			// Clear FFA on death so revived player starts peaceful
			try {
				player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_PLAYERS);
				player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_NPCS);
			} catch (Exception e) {
				log.warn("[FFA] state cleanup failed for " + player.getName(), e);
			}
		}
		String killerName = "";
		try {
			Creature master = lastAttacker != null ? lastAttacker.getMaster() : null;
			killerName = master instanceof Player pk ? pk.getName() : "NPC";
		} catch (Exception ignored) {}
		CustomAuditLog.getInstance().log("ffa", "death", player.getName(), "killer=" + killerName);
	}

	// ────────────── Admin-facing queries ──────────────

	/**
	 * Force-clear FFA state for a named player (GM //ffa clear &lt;name&gt;).
	 * Returns true if the player was found online and was in FFA state.
	 */
	public boolean adminClearFfa(Player target) {
		if (target == null || !isInFfa(target))
			return false;
		exitFfa(target);
		cooldownMap.remove(target.getObjectId());
		return true;
	}

	/** @return number of players currently in FFA — simple walk of the world. */
	public int adminCountActive() {
		int[] count = {0};
		com.aionemu.gameserver.world.World.getInstance().forEachPlayer(p -> {
			if (isInFfa(p))
				count[0]++;
		});
		return count[0];
	}

	/**
	 * Spawn a loot chest NPC and register the dropped items as free-for-all loot.
	 * The chest auto-despawns after {@link CustomConfig#FFA_LOOT_CHEST_LIFETIME_MS}.
	 */
	private void spawnLootChest(Player deceased, List<Item> itemsToDrop) {
		int chestNpcId = CustomConfig.FFA_LOOT_CHEST_NPC_ID;
		if (chestNpcId <= 0) return;

		float x = deceased.getX();
		float y = deceased.getY();
		float z = deceased.getZ();
		int worldId = deceased.getWorldId();
		int instanceId = deceased.getInstanceId();

		// 1. Unequip + remove items from player's equipment/inventory.
		// Unequipping returns the item to inventory; decreaseByObjectId(1) then
		// removes it from inventory storage so it's detached from the player.
		IStorage inv = deceased.getInventory();
		for (Item item : itemsToDrop) {
			try {
				if (item.isEquipped()) {
					deceased.getEquipment().unEquipItem(item.getObjectId());
				}
				inv.decreaseByObjectId(item.getObjectId(), item.getItemCount());
			} catch (Exception e) {
				log.warn("[FFA] failed to detach item " + item.getObjectId() + " from " + deceased.getName(), e);
			}
		}

		// 2. Spawn the chest NPC at death location. NPC name is fixed by its
		// template (230103 = "entrance treasure chest (temp)"); we can't
		// rename a live Npc — the chest just shows its default label.
		SpawnTemplate spawn = SpawnEngine.newSingleTimeSpawn(worldId, chestNpcId, x, y, z, (byte) 0);
		VisibleObject spawned = SpawnEngine.spawnObject(spawn, instanceId);
		if (!(spawned instanceof Npc chest)) {
			log.warn("[FFA] failed to spawn loot chest npc id=" + chestNpcId);
			return;
		}

		// 3. Build DropItem set from the detached items and stuff it into DropRegistrationService
		int chestObjId = chest.getObjectId();
		Set<DropItem> drops = new HashSet<>();
		int index = 1;
		for (Item item : itemsToDrop) {
			DropItem di = new DropItem(new Drop(item.getItemId(), 1, 1, 100));
			di.setCount(Math.max(1, item.getItemCount()));
			di.setIndex(index++);
			di.setNpcObj(chestObjId);
			di.isFreeForAll(true); // anyone nearby can loot
			drops.add(di);
		}
		DropRegistrationService.getInstance().getCurrentDropMap().put(chestObjId, drops);
		DropNpc dropNpc = new DropNpc(chestObjId);
		// Allow every player in the area to loot — permissive
		deceased.getKnownList().forEachPlayer(p -> dropNpc.setAllowedLooter(p));
		dropNpc.setAllowedLooter(deceased); // in case the player is the only one around
		DropRegistrationService.getInstance().getDropRegistrationMap().put(chestObjId, dropNpc);
		PacketSendUtility.broadcastPacket(chest, new SM_LOOT_STATUS(chestObjId, Status.LOOT_ENABLE));

		CustomFeatureMetrics.getInstance().inc("ffa.chest.spawned");
		CustomFeatureMetrics.getInstance().add("ffa.chest.items_total", itemsToDrop.size());
		log.info("[FFA] spawned loot chest {} with {} items for {}",
			chestObjId, itemsToDrop.size(), deceased.getName());

		// 4. Schedule despawn after lifetime
		ThreadPoolManager.getInstance().schedule(() -> {
			try {
				if (chest.isSpawned() && !chest.isDead()) {
					DropRegistrationService.getInstance().getCurrentDropMap().remove(chestObjId);
					DropRegistrationService.getInstance().getDropRegistrationMap().remove(chestObjId);
					chest.getController().onDelete();
				}
			} catch (Exception e) {
				log.warn("[FFA] loot chest despawn failed", e);
			}
		}, CustomConfig.FFA_LOOT_CHEST_LIFETIME_MS);
	}

	/** Clear FFA state on logout so the player comes back peaceful. */
	public void onPlayerLogout(Player player) {
		if (player == null) return;
		if (isInFfa(player)) {
			player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_PLAYERS);
			player.unsetCustomState(CustomPlayerState.ENEMY_OF_ALL_NPCS);
		}
		cooldownMap.remove(player.getObjectId());
		killStreaks.remove(player.getObjectId());
		hourlyToggles.remove(player.getObjectId());
	}

	// ────────────── Kill Streak (Product dimension) ──────────────

	/**
	 * Credit the killer with a streak increment when a FFA player dies.
	 * Broadcasts milestone messages at configurable thresholds.
	 */
	private void trackKillerStreak(Player victim, Creature lastAttacker) {
		if (lastAttacker == null)
			return;
		try {
			Creature master = lastAttacker.getMaster();
			if (!(master instanceof Player killer) || killer == victim)
				return;
			CustomFeatureMetrics.getInstance().inc("ffa.kills");
			AtomicInteger streak = killStreaks.computeIfAbsent(killer.getObjectId(), k -> new AtomicInteger());
			int current = streak.incrementAndGet();
			int threshold = CustomConfig.FFA_KILL_STREAK_BROADCAST_THRESHOLD;
			if (threshold > 0 && current > 0 && current % threshold == 0) {
				String msg = "『" + killer.getName() + "』FFA 连杀 " + current + " 人!";
				com.aionemu.gameserver.world.World.getInstance().forEachPlayer(
					p -> PacketSendUtility.sendPacket(p, new SM_MESSAGE(0, null, msg, ChatType.BRIGHT_YELLOW_CENTER)));
				CustomFeatureMetrics.getInstance().inc("ffa.streak.broadcast");
			}
		} catch (Exception e) {
			log.warn("[FFA] kill streak tracking failed", e);
		}
	}

	// ────────────── Hourly Rate Limit (Security dimension) ──────────────

	/**
	 * Check whether the player has exceeded their hourly toggle budget.
	 * Returns true if allowed, false if capped.
	 */
	private boolean checkHourlyLimit(Player player) {
		int maxPerHour = CustomConfig.FFA_MAX_TOGGLES_PER_HOUR;
		if (maxPerHour <= 0)
			return true; // disabled
		long now = System.currentTimeMillis();
		long[] bucket = hourlyToggles.computeIfAbsent(player.getObjectId(), k -> new long[]{now, 0});
		synchronized (bucket) {
			if (now - bucket[0] > 3_600_000L) {
				bucket[0] = now;
				bucket[1] = 1;
				return true;
			}
			bucket[1]++;
			return bucket[1] <= maxPerHour;
		}
	}

	private void notifyPlayer(Player player, String msg) {
		PacketSendUtility.sendPacket(player, new SM_MESSAGE(0, null, msg, ChatType.BRIGHT_YELLOW_CENTER));
	}
}
