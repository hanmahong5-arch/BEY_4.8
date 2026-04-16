package com.aionemu.gameserver.services;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dao.ChronicleDAO;
import com.aionemu.gameserver.dao.HeirloomDAO;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemQuality;

/**
 * Heirloom Service — Shiguang Living World
 *
 * Governs the scarcity economy: each epoch only 3 heirloom items can drop
 * from world bosses (NPC rating HERO+). Every ownership change is permanently
 * recorded and broadcast to all players, creating shared server history.
 *
 * Design decisions:
 *   - HEIRLOOM_QUOTA is intentionally small (3) to maximise scarcity tension.
 *   - Minimum drop quality is LEGENDARY to prevent flooding.
 *   - An in-memory registry caches known heirloom obj IDs to avoid per-pickup DB reads.
 *   - All failures are swallowed; heirloom logic must never block item flow.
 */
public class HeirloomService {

    private static final Logger log = LoggerFactory.getLogger(HeirloomService.class);

    /** Maximum number of heirloom items that can drop per epoch. */
    public static final int HEIRLOOM_QUOTA = 3;

    /** Minimum item quality required to be considered for heirloom status (EPIC = Eternal/Orange and above). */
    private static final ItemQuality MIN_QUALITY = ItemQuality.EPIC;

    /**
     * In-memory cache of item object IDs that are confirmed heirlooms.
     * Populated lazily on first check, avoids repeated DB reads per pickup.
     */
    private static final Set<Long> knownHeirlooms = ConcurrentHashMap.newKeySet();

    // -- Drop hook --------------------------------------------------------------

    /**
     * Called from DropService after an item is successfully placed into a player's inventory.
     * Decides whether the item qualifies as an heirloom and, if so, registers it,
     * broadcasts to all players, and records in the chronicle.
     *
     * @param player    player who received the item
     * @param item      the item that was received
     * @param sourceNpc the NPC it dropped from, may be null (e.g. chest)
     */
    public static void onItemDropped(Player player, Item item, Npc sourceNpc) {
        try {
            if (!qualifiesForHeirloom(item))
                return;

            int epochId = ChronicleDAO.getCurrentEpochId();
            int alreadyDropped = HeirloomDAO.countDroppedThisEpoch(epochId);

            if (alreadyDropped >= HEIRLOOM_QUOTA)
                return; // quota exhausted this epoch

            // Register in DB
            int npcId = sourceNpc != null ? sourceNpc.getNpcId() : 0;
            HeirloomDAO.registerHeirloom(item.getObjectId(), epochId,
                    item.getItemId(), npcId, player.getName());

            // Cache locally
            knownHeirlooms.add((long) item.getObjectId());

            int remaining = HEIRLOOM_QUOTA - alreadyDropped - 1;
            String itemName = item.getItemTemplate().getL10n();
            String locationName = player.getWorldId() + ""; // fallback; override if L10n is available

            // Broadcast center-screen legendary announcement
            NarrativeAnnouncementService.broadcastHeirloomDrop(player.getName(), itemName, remaining);

            // Persist to chronicle
            ChronicleService.recordHeirloomDrop(player.getName(), itemName, locationName);

            log.info("Heirloom registered: [{}] {} -> {} (epoch {}, {}/{} used)",
                    item.getObjectId(), itemName, player.getName(), epochId,
                    alreadyDropped + 1, HEIRLOOM_QUOTA);

        } catch (Exception e) {
            // Never let heirloom logic interrupt item pickup
            log.error("Heirloom check failed for player {} item {}", player.getName(), item.getObjectId(), e);
        }
    }

    // -- Transfer hook ---------------------------------------------------------

    /**
     * Called when a player gives or sells an item to another player.
     * If the item is a registered heirloom, records the transfer and broadcasts globally.
     *
     * @param item       the item changing hands
     * @param fromPlayer the current owner (seller/giver)
     * @param toPlayer   the new owner (buyer/recipient)
     */
    public static void onItemTransfer(Item item, Player fromPlayer, Player toPlayer) {
        try {
            if (!isKnownHeirloom(item.getObjectId()))
                return;

            String itemName  = item.getItemTemplate().getL10n();
            String fromName  = fromPlayer.getName();
            String toName    = toPlayer.getName();

            HeirloomDAO.updateOwner(item.getObjectId(), fromName, toName);
            NarrativeAnnouncementService.broadcastHeirloomTransfer(itemName, fromName, toName);
            ChronicleService.recordHeirloomTransfer(itemName, fromName, toName);

            log.info("Heirloom transferred: [{}] {} {} → {}", item.getObjectId(), itemName, fromName, toName);

        } catch (Exception e) {
            log.error("Heirloom transfer hook failed for item {}", item.getObjectId(), e);
        }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Returns true if the item is already confirmed as an heirloom.
     * Checks the local cache first, then falls back to DB.
     */
    public static boolean isKnownHeirloom(int itemObjId) {
        long id = (long) itemObjId;
        if (knownHeirlooms.contains(id))
            return true;
        // Lazy DB lookup (e.g. after server restart)
        if (HeirloomDAO.isHeirloom(id)) {
            knownHeirlooms.add(id);
            return true;
        }
        return false;
    }

    /**
     * An item qualifies for heirloom consideration if it meets the minimum quality
     * threshold. The quota check happens later in onItemDropped.
     */
    private static boolean qualifiesForHeirloom(Item item) {
        if (item == null || item.getItemTemplate() == null)
            return false;
        ItemQuality quality = item.getItemTemplate().getItemQuality();
        return quality != null && quality.getQualityId() >= MIN_QUALITY.getQualityId();
    }
}
