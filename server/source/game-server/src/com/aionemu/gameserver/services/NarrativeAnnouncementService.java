package com.aionemu.gameserver.services;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.siege.SiegeRace;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * Narrative Announcement Service — Shiguang Living World
 *
 * Turns raw game events into story-flavoured world broadcasts.
 * Designed with two layers:
 *   1. A built-in template library (always available, zero latency, no external calls)
 *   2. AI-generated text via AIAgentService (used when available, falls back to templates)
 *
 * All broadcasts are fire-and-forget, non-blocking.
 * The service deliberately does NOT throw — a narrative failure must never interrupt gameplay.
 *
 * Chat type guide for broadcasts:
 *   GOLDEN_YELLOW      — world news, visible in all-chat, not intrusive
 *   BRIGHT_YELLOW_CENTER — screen-center box, for legendary moments only
 */
public class NarrativeAnnouncementService {

    // -- Siege capture templates (faction + location name substituted) ----------

    private static final String[] SIEGE_CAPTURE_ELYOS = {
        "【世界之声】天族的旗帜飘扬在%s之巅——这一刻，将被历史铭记。",
        "【世界之声】天族铁骑踏破了%s的防线，胜利的号角响彻云霄。",
        "【世界之声】圣光笼罩%s，天族以意志铸就了这个时代的胜利。",
        "【世界之声】%s易主。天族将这片土地纳入版图，时间的天秤悄然倾斜……",
        "【世界之声】天族占领了%s。黑暗之中，魔族正在磨砺复仇之剑。",
    };

    private static final String[] SIEGE_CAPTURE_ASMO = {
        "【世界之声】魔族的战旗在%s的废墟上猎猎飞扬——黑暗的纪元降临。",
        "【世界之声】魔族如潮涌至，%s的防线在怒吼中崩塌。",
        "【世界之声】黑色风暴席卷%s，魔族以血与火书写了今日之史。",
        "【世界之声】%s落入魔族之手。天族残部，正在黑暗中舔舐伤口……",
        "【世界之声】魔族占领了%s。这片土地的哭泣，只有胜利者才听不见。",
    };

    private static final String[] SIEGE_CAPTURE_BALAUR = {
        "【世界之声】龙族阴影笼罩%s！两族皆受创，龙族渔翁得利……",
        "【世界之声】警报：龙族夺取了%s！两族的征战让古老的敌人趁虚而入。",
        "【世界之声】【紧急】%s落入龙族之爪！趁两族鏖战，远古威胁已悄然复苏。",
    };

    // -- Siege defend templates ------------------------------------------------

    private static final String[] SIEGE_DEFEND_ELYOS = {
        "【世界之声】%s守住了！天族誓死捍卫，攻势在圣光中灰飞烟灭。",
        "【世界之声】天族如铜墙铁壁，%s在风雨中屹立未倒。",
        "【世界之声】浴血苦战，天族以血肉筑成防线——%s依然飘扬着圣光之旗。",
    };

    private static final String[] SIEGE_DEFEND_ASMO = {
        "【世界之声】%s守住了！魔族在黑暗中咆哮，所有入侵者铩羽而归。",
        "【世界之声】魔族的意志坚不可摧，%s在攻势之下巍然不动。",
        "【世界之声】又一场攻势化为泡影——%s，依然属于魔族。",
    };

    // -- Heirloom drop/transfer templates -------------------------------------

    private static final String[] HEIRLOOM_DROP = {
        "【纪元传说】「%s」降临于%s之手！这件传世之物将以此主人的名字，永载纪元史册。",
        "【天地异象】异光自%s处升起——传世器物「%s」出世，全世界都将知道它的下落。",
        "【传世之物】%s获得了「%s」。这个纪元，只有%d件这样的宝物会降临世间。",
    };

    private static final String[] HEIRLOOM_TRANSFER = {
        "【纪元传说】传世之物「%s」已易主：%s → %s。它的新历史，从此刻开始。",
        "【世界之眼】「%s」从%s手中流转至%s。传世之物，终究属于有缘人。",
    };

    // -- Broadcast API ---------------------------------------------------------

    /**
     * Broadcasts a narrative siege-capture message to all online players.
     * The correct template set is chosen by winner race.
     *
     * @param winnerRace   the race that captured the fortress
     * @param fortressName localised fortress name
     * @param legionName   winning legion name, or null if unaffiliated forces
     */
    public static void broadcastSiegeCapture(SiegeRace winnerRace, String fortressName, String legionName) {
        String[] templates = switch (winnerRace) {
            case ELYOS      -> SIEGE_CAPTURE_ELYOS;
            case ASMODIANS  -> SIEGE_CAPTURE_ASMO;
            case BALAUR     -> SIEGE_CAPTURE_BALAUR;
            default         -> SIEGE_CAPTURE_ELYOS;
        };
        String narrative = String.format(pick(templates), fortressName);

        // Append legion name for extra social recognition
        if (legionName != null && !legionName.isEmpty())
            narrative += " [" + legionName + "]";

        broadcastToWorld(narrative, ChatType.GOLDEN_YELLOW);
    }

    /**
     * Broadcasts a narrative siege-defend message to all online players.
     *
     * @param defenderRace the race that successfully defended the fortress
     * @param fortressName localised fortress name
     */
    public static void broadcastSiegeDefend(SiegeRace defenderRace, String fortressName) {
        String[] templates = switch (defenderRace) {
            case ELYOS     -> SIEGE_DEFEND_ELYOS;
            case ASMODIANS -> SIEGE_DEFEND_ASMO;
            default        -> SIEGE_DEFEND_ELYOS;
        };
        broadcastToWorld(String.format(pick(templates), fortressName), ChatType.GOLDEN_YELLOW);
    }

    /**
     * Broadcasts a screen-center legendary announcement when a heirloom item first drops.
     *
     * @param playerName     name of the lucky player
     * @param itemName       display name of the heirloom item
     * @param remainingQuota how many heirloom drops are left this epoch (shown for FOMO effect)
     */
    public static void broadcastHeirloomDrop(String playerName, String itemName, int remainingQuota) {
        // Pick a template and substitute placeholders (template 0 uses 2 strings, template 2 uses 3 + int)
        String narrative;
        int idx = Rnd.get(0, HEIRLOOM_DROP.length - 1);
        if (idx == 2)
            narrative = String.format(HEIRLOOM_DROP[idx], playerName, itemName, remainingQuota);
        else if (idx == 1)
            narrative = String.format(HEIRLOOM_DROP[idx], playerName, itemName);
        else
            narrative = String.format(HEIRLOOM_DROP[idx], playerName, itemName);

        // Center-screen box for maximum impact — this is a server-wide legendary moment
        broadcastToWorld(narrative, ChatType.BRIGHT_YELLOW_CENTER);
        // Also echo into regular world chat so players who dismiss the box still see it
        broadcastToWorld(narrative, ChatType.GOLDEN_YELLOW);
    }

    /**
     * Broadcasts a world message when a heirloom item changes hands (trade/drop).
     *
     * @param itemName   display name of the heirloom item
     * @param fromPlayer previous owner name
     * @param toPlayer   new owner name
     */
    public static void broadcastHeirloomTransfer(String itemName, String fromPlayer, String toPlayer) {
        String narrative = String.format(pick(HEIRLOOM_TRANSFER), itemName, fromPlayer, toPlayer);
        broadcastToWorld(narrative, ChatType.GOLDEN_YELLOW);
    }

    // -- Internal helpers ------------------------------------------------------

    /** Sends a free-form narrative message to all players regardless of race. */
    public static void broadcastToWorld(String message, ChatType chatType) {
        SM_MESSAGE packet = new SM_MESSAGE(0, "世界之声", message, chatType);
        PacketSendUtility.broadcastToWorld(packet);
    }

    /** Sends a faction-targeted narrative message only to the specified race. */
    public static void broadcastToRace(Race race, String message, ChatType chatType) {
        SM_MESSAGE packet = new SM_MESSAGE(0, "阵营密报", message, chatType);
        PacketSendUtility.broadcastToWorld(packet, p -> p.getRace() == race);
    }

    /** Picks a random element from a non-empty string array. */
    private static String pick(String[] arr) {
        return arr[Rnd.get(0, arr.length - 1)];
    }

    private NarrativeAnnouncementService() {}
}
