package com.aionemu.gameserver.management;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.cache.HTMLCache;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.dataholders.StaticData;
import com.aionemu.gameserver.dataholders.loadingutils.XmlDataLoader;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

/**
 * Hot-reload service for live game data updates.
 *
 * Enables the "living world" pattern: external agents (AI, admin tools, REST API)
 * can modify game data files, then trigger a reload. Online players see changes
 * immediately without server restart.
 *
 * Architecture:
 *   Agent modifies XML → POST /api/reload/{target}
 *                       → HotReloadService.reload(target)
 *                       → DataManager fields refreshed
 *                       → SM_SYSTEM_MESSAGE broadcast
 *                       → Players experience the change
 *
 * Thread safety: All reloads run on a single-threaded executor to prevent
 * concurrent JAXB parsing. DataManager field assignments are atomic (reference swap).
 */
public class HotReloadService {

    private static final Logger log = LoggerFactory.getLogger(HotReloadService.class);
    private static final AtomicInteger reloadCounter = new AtomicInteger(0);

    /**
     * Registry of reloadable data targets.
     * Each entry maps a target name to its reload action.
     */
    private static final Map<String, Supplier<String>> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("html", HotReloadService::reloadHtml);
        TARGETS.put("items", HotReloadService::reloadItems);
        TARGETS.put("npcs", HotReloadService::reloadNpcs);
        TARGETS.put("skills", HotReloadService::reloadSkills);
        TARGETS.put("quests", HotReloadService::reloadQuests);
        TARGETS.put("drops", HotReloadService::reloadDrops);
        TARGETS.put("all", HotReloadService::reloadAll);
    }

    /**
     * Reload a specific data target. Returns JSON result.
     *
     * @param target one of: html, items, npcs, skills, quests, drops, all
     * @return JSON response with status and details
     */
    public static String reload(String target) {
        Supplier<String> action = TARGETS.get(target.toLowerCase());
        if (action == null) {
            return "{\"status\":\"error\",\"message\":\"Unknown target: " + target
                + "\",\"available\":[" + String.join(",",
                    TARGETS.keySet().stream().map(k -> "\"" + k + "\"").toList())
                + "]}";
        }

        long start = System.currentTimeMillis();
        try {
            String detail = action.get();
            long elapsed = System.currentTimeMillis() - start;
            int seq = reloadCounter.incrementAndGet();

            // Broadcast to all online players
            broadcastReload(target);

            log.info("[HotReload] #{} Reloaded '{}' in {}ms: {}", seq, target, elapsed, detail);
            return "{\"status\":\"ok\",\"target\":\"" + target + "\",\"detail\":\"" + detail
                + "\",\"elapsed_ms\":" + elapsed + ",\"reload_seq\":" + seq + "}";
        } catch (Exception e) {
            log.error("[HotReload] Failed to reload '{}'", target, e);
            return "{\"status\":\"error\",\"target\":\"" + target
                + "\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // --- Individual reload actions ---

    private static String reloadHtml() {
        HTMLCache.getInstance().reload(true);
        return "HTML dialogue cache refreshed";
    }

    private static String reloadItems() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.ITEM_DATA = data.itemData;
        int count = DataManager.ITEM_DATA.size();
        return count + " item templates reloaded";
    }

    private static String reloadNpcs() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.NPC_DATA = data.npcData;
        int count = DataManager.NPC_DATA.size();
        return count + " NPC templates reloaded";
    }

    private static String reloadSkills() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.SKILL_DATA = data.skillData;
        int count = DataManager.SKILL_DATA.size();
        return count + " skill templates reloaded";
    }

    private static String reloadQuests() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.QUEST_DATA = data.questData;
        int count = DataManager.QUEST_DATA.size();
        return count + " quest templates reloaded";
    }

    private static String reloadDrops() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.GLOBAL_DROP_DATA = data.globalDropData;
        return "Global drop data reloaded";
    }

    private static String reloadAll() {
        StaticData data = XmlDataLoader.loadStaticData();
        DataManager.ITEM_DATA = data.itemData;
        DataManager.NPC_DATA = data.npcData;
        DataManager.SKILL_DATA = data.skillData;
        DataManager.QUEST_DATA = data.questData;
        DataManager.GLOBAL_DROP_DATA = data.globalDropData;
        HTMLCache.getInstance().reload(true);
        return "All data holders refreshed";
    }

    // --- Notification ---

    private static void broadcastReload(String target) {
        try {
            World.getInstance().forEachPlayer(player -> {
                PacketSendUtility.sendMessage(player,
                    "[System] Game data updated: " + target);
            });
        } catch (Exception e) {
            // World might not be initialized during early startup
        }
    }
}
