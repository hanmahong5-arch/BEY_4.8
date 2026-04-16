package com.aionemu.gameserver.services.ffa;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.drop.Drop;
import com.aionemu.gameserver.model.drop.DropItem;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.items.NpcEquippedGear;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate;
import com.aionemu.gameserver.model.templates.tradelist.TradeListTemplate.TradeTab;
import com.aionemu.gameserver.services.drop.DropRegistrationService;

/**
 * Injects extra drops into an NPC's post-death loot map based on:
 * <ul>
 *   <li><b>穿啥掉啥</b> — NPC's {@link NpcEquippedGear} (visible equipment slots).
 *       Per-slot chance = {@link CustomConfig#NPC_EQUIP_DROP_CHANCE}.</li>
 *   <li><b>卖啥掉啥</b> — if the NPC is a merchant, items from its
 *       {@link TradeListTemplate}. Per-item chance = {@link CustomConfig#NPC_SELL_DROP_CHANCE}.</li>
 * </ul>
 *
 * <p>Boss NPCs get a {@link CustomConfig#NPC_BOSS_DROP_MULT}× multiplier on both chances.
 *
 * <p>Runs AFTER the standard {@code DropRegistrationService.registerDrop} has seeded
 * the currentDropMap — we then append our bonus drops to the same entry so the
 * existing loot UI picks them up automatically. No separate loot window.
 *
 * <p>Thread-safety: only writes happen on the game-server kill path, which is
 * single-threaded per-NPC. The underlying map is ConcurrentHashMap.
 *
 * @author BEY_4.8 FFA feature
 */
public final class NpcLootInjector {

	private static final Logger log = LoggerFactory.getLogger(NpcLootInjector.class);
	private static final NpcLootInjector INSTANCE = new NpcLootInjector();

	private NpcLootInjector() {}

	public static NpcLootInjector getInstance() {
		return INSTANCE;
	}

	/**
	 * Roll and inject equip + sell bonus drops for a freshly-killed NPC.
	 * Must be called AFTER {@code doReward()} so currentDropMap has the base entry.
	 */
	public void injectBonusDrops(Npc npc, Player topDamager) {
		if (npc == null || topDamager == null) return;

		int npcObjId = npc.getObjectId();
		Map<Integer, Set<DropItem>> dropMap = DropRegistrationService.getInstance().getCurrentDropMap();
		Set<DropItem> existingDrops = dropMap.get(npcObjId);
		if (existingDrops == null) return; // no drop was registered (npc may not have been looted)

		double bossMult = npc.isBoss() ? CustomConfig.NPC_BOSS_DROP_MULT : 1.0;
		double equipChance = CustomConfig.NPC_EQUIP_DROP_CHANCE * bossMult;
		double sellChance = CustomConfig.NPC_SELL_DROP_CHANCE * bossMult;

		// Compute the next free index so our injected drops don't collide with
		// the base drops already placed by DropRegistrationService.
		int nextIndex = existingDrops.stream().mapToInt(DropItem::getIndex).max().orElse(0) + 1;

		int injected = 0;

		int equipDrops = 0;
		int sellDrops = 0;

		// ---- 穿啥掉啥：iterate NpcEquippedGear ----
		try {
			NpcTemplate tpl = npc.getObjectTemplate();
			if (tpl != null && tpl.getEquipment() != null) {
				for (Entry<ItemSlot, ItemTemplate> entry : tpl.getEquipment()) {
					if (entry == null) continue;
					ItemTemplate equip = entry.getValue();
					if (equip == null || equip.getTemplateId() <= 0) continue;
					if (Rnd.chance() < equipChance * 100f) {
						DropItem di = buildDrop(nextIndex++, npcObjId, equip.getTemplateId(), 1, topDamager.getObjectId());
						existingDrops.add(di);
						equipDrops++;
						injected++;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[NpcLoot] equip drop roll failed for npc " + npc.getNpcId(), e);
		}

		// ---- 卖啥掉啥：iterate trade list if merchant ----
		try {
			TradeListTemplate trade = DataManager.TRADE_LIST_DATA == null
				? null : DataManager.TRADE_LIST_DATA.getTradeListTemplate(npc.getNpcId());
			if (trade != null && trade.getTradeTablist() != null) {
				for (TradeTab tab : trade.getTradeTablist()) {
					if (tab == null) continue;
					int itemId = tab.getId();
					if (itemId <= 0) continue;
					if (Rnd.chance() < sellChance * 100f) {
						DropItem di = buildDrop(nextIndex++, npcObjId, itemId, 1, topDamager.getObjectId());
						existingDrops.add(di);
						sellDrops++;
						injected++;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[NpcLoot] sell drop roll failed for npc " + npc.getNpcId(), e);
		}

		if (equipDrops > 0)
			CustomFeatureMetrics.getInstance().add("npc.hardcore.bonus_equip_drops", equipDrops);
		if (sellDrops > 0)
			CustomFeatureMetrics.getInstance().add("npc.hardcore.bonus_sell_drops", sellDrops);

		if (injected > 0 && log.isDebugEnabled()) {
			log.debug("[NpcLoot] {} bonus drops injected into npc {} (boss={}, killer={})",
				injected, npc.getNpcId(), npc.isBoss(), topDamager.getName());
		}
	}

	private DropItem buildDrop(int index, int npcObjId, int itemId, long count, int winnerObjId) {
		DropItem di = new DropItem(new Drop(itemId, 1, 1, 100));
		di.setCount(Math.max(1, count));
		di.setIndex(index);
		di.setNpcObj(npcObjId);
		di.setPlayerObjId(winnerObjId);
		return di;
	}
}
