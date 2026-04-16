package com.aionemu.gameserver.services;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dao.ChronicleDAO;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.siege.SiegeRace;
import com.aionemu.gameserver.model.team.legion.Legion;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.model.ChatType;

/**
 * Chronicle Service — Shiguang Living World
 *
 * The world's memory. Every history-making event is routed through this service
 * to be persisted in world_chronicle and, where appropriate, shown to players
 * via the .chronicle and .epoch commands.
 *
 * Design contract:
 *   - Never throws; all DB failures are logged and absorbed.
 *   - Writes are async-safe (DB calls are the only blocking operations).
 *   - Callers should not wait on this service's return value for game logic.
 */
public class ChronicleService {

    private static final Logger log = LoggerFactory.getLogger(ChronicleService.class);

    // -- Public event recorders ------------------------------------------------

    /**
     * Records the capture or successful defence of a fortress.
     *
     * @param winnerRace   the race that now holds the fortress
     * @param captured     true = fortress captured; false = successfully defended
     * @param locationId   internal siege location ID
     * @param locationName localised fortress name
     * @param winnerLegion the top-contributing legion, or null
     */
    public static void recordSiegeOutcome(SiegeRace winnerRace, boolean captured,
            int locationId, String locationName, Legion winnerLegion) {
        int epochId = ChronicleDAO.getCurrentEpochId();

        String eventType  = captured ? "SIEGE_CAPTURE" : "SIEGE_DEFEND";
        String faction    = toFactionString(winnerRace);
        String protagonist = winnerLegion != null ? winnerLegion.getName() : null;

        String title;
        if (captured) {
            title = protagonist != null
                ? "[" + faction + "]「" + protagonist + "」占领了" + locationName
                : "[" + faction + "]占领了" + locationName;
        } else {
            title = "[" + faction + "]守住了" + locationName;
        }

        int importance = captured ? 2 : 3;

        ChronicleDAO.insertEvent(epochId, eventType, faction, locationId, locationName,
                protagonist, title, null, importance);

        log.debug("Chronicle: {} - {}", eventType, title);
    }

    /**
     * Records an heirloom item first-drop event.
     *
     * @param playerName     player who received the item
     * @param itemName       display name of the item
     * @param locationName   where it dropped (zone/map name)
     */
    public static void recordHeirloomDrop(String playerName, String itemName, String locationName) {
        int epochId = ChronicleDAO.getCurrentEpochId();
        String title = playerName + "获得了传世之物「" + itemName + "」于" + locationName;
        ChronicleDAO.insertEvent(epochId, "HEIRLOOM_DROP", null, 0, locationName,
                playerName, title, null, 1);
    }

    /**
     * Records an heirloom transfer between players.
     *
     * @param itemName   display name of the item
     * @param fromPlayer previous owner
     * @param toPlayer   new owner
     */
    public static void recordHeirloomTransfer(String itemName, String fromPlayer, String toPlayer) {
        int epochId = ChronicleDAO.getCurrentEpochId();
        String title = "「" + itemName + "」从" + fromPlayer + "传至" + toPlayer;
        ChronicleDAO.insertEvent(epochId, "HEIRLOOM_TRANSFER", null, 0, null,
                toPlayer, title, null, 2);
    }

    // -- Player command handler (.chronicle) -----------------------------------

    /**
     * Sends the last N chronicle entries to the requesting player via system chat.
     * Called from the .chronicle player command handler.
     *
     * @param player  the requesting player
     * @param count   number of entries to show (capped at 10)
     */
    public static void sendChronicleToPlayer(Player player, int count) {
        int capped = Math.min(count, 10);
        List<String> events = ChronicleDAO.getRecentEvents(capped);

        if (events.isEmpty()) {
            sendMsg(player, "【编年史】此纪元尚未留下值得铭记的历史……");
            return;
        }

        sendMsg(player, "══ 纪元编年史 ══");
        for (String event : events) {
            sendMsg(player, event);
        }
        sendMsg(player, "══════════════");
    }

    // -- Helpers ---------------------------------------------------------------

    private static String toFactionString(SiegeRace race) {
        return switch (race) {
            case ELYOS     -> "ELYOS";
            case ASMODIANS -> "ASMO";
            case BALAUR    -> "BALAUR";
            default        -> "UNKNOWN";
        };
    }

    private static void sendMsg(Player player, String text) {
        PacketSendUtility.sendPacket(player, new SM_MESSAGE(0, "编年史官", text, ChatType.GOLDEN_YELLOW));
    }

    private ChronicleService() {}
}
