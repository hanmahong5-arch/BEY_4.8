package com.aionemu.gameserver.management;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.HTMLService;
import com.aionemu.gameserver.services.AIAgentService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

/**
 * Lightweight management REST API for agent integration and monitoring.
 * Uses JDK built-in HttpServer — zero external dependencies.
 *
 * Endpoints:
 *   GET  /api/health      — Server health status (JSON)
 *   GET  /api/status      — Detailed server metrics (JSON)
 *   POST /api/reload/{t}  — Hot-reload game data
 *   POST /api/ai/push     — AI Agent push message to player
 *   GET  /api/ai/players  — List online players for AI targeting
 *
 * Designed for:
 *   - AI agent integration (structured JSON responses)
 *   - Monitoring dashboards (Grafana, custom)
 *   - ChatOps / CLI automation (curl-friendly)
 */
public class ManagementServer {

    private static final Logger log = LoggerFactory.getLogger(ManagementServer.class);
    private static final Instant START_TIME = Instant.now();

    private HttpServer server;
    private final int port;

    public ManagementServer(int port) {
        this.port = port;
    }

    /** Start the management HTTP server on a virtual thread pool. */
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/api/health", this::handleHealth);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/reload/", this::handleReload);
            server.createContext("/api/ai/push", this::handleAIPush);
            server.createContext("/api/ai/players", this::handleAIPlayers);

            server.start();
            log.info("[ManagementAPI] Listening on http://127.0.0.1:{}/api/", port);
        } catch (IOException e) {
            log.error("[ManagementAPI] Failed to start on port {}: {}", port, e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("[ManagementAPI] Stopped");
        }
    }

    // --- Handlers ---

    private void handleHealth(HttpExchange ex) throws IOException {
        String json = """
            {"status":"ok","uptime":"%s","port":%d}
            """.formatted(formatUptime(), port);
        respond(ex, 200, json);
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        int online = getOnlineCount();

        String json = """
            {
              "status": "ok",
              "uptime": "%s",
              "players_online": %d,
              "memory_used_mb": %d,
              "memory_max_mb": %d,
              "jvm_threads": %d,
              "gc_count": %d
            }
            """.formatted(
                formatUptime(), online, usedMB, maxMB,
                Thread.activeCount(),
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> gc.getCollectionCount()).sum()
            );
        respond(ex, 200, json);
    }

    private void handleReload(HttpExchange ex) throws IOException {
        // Extract target from path: /api/reload/items → "items"
        String path = ex.getRequestURI().getPath();
        String target = path.substring("/api/reload/".length()).trim();
        if (target.isEmpty()) target = "all";

        String result = HotReloadService.reload(target);
        int code = result.contains("\"error\"") ? 400 : 200;
        respond(ex, code, result);
    }

    // --- AI Agent Handlers ---

    /**
     * POST /api/ai/push — Push a message from AI to a specific player or all players.
     *
     * Request body (JSON):
     *   {"player_name":"Warrior","type":"chat","message":"Hello from AI!"}
     *   {"player_name":"*","type":"broadcast","message":"Server event!"}
     *   {"player_name":"Warrior","type":"html","message":"<html>...</html>"}
     *   {"player_name":"Warrior","type":"chat_center","message":"Important!"}
     *
     * Types: chat, chat_center, html, broadcast
     */
    private void handleAIPush(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"POST only\"}");
            return;
        }

        String body = readBody(ex);
        String playerName = extractJsonValue(body, "player_name");
        String type = extractJsonValue(body, "type");
        String message = extractJsonValue(body, "message");

        if (type == null || message == null) {
            respond(ex, 400, "{\"error\":\"Missing type or message\"}");
            return;
        }

        // Broadcast to all players
        if ("broadcast".equals(type) || "*".equals(playerName)) {
            AIAgentService.getInstance().pushBroadcast(message);
            respond(ex, 200, "{\"status\":\"ok\",\"pushed_to\":\"all\"}");
            return;
        }

        if (playerName == null || playerName.isEmpty()) {
            respond(ex, 400, "{\"error\":\"Missing player_name\"}");
            return;
        }

        // Find target player
        Player target = findPlayerByName(playerName);
        if (target == null) {
            respond(ex, 404, "{\"error\":\"Player not found or offline\"}");
            return;
        }

        switch (type) {
            case "chat" -> AIAgentService.getInstance().pushChat(target, message);
            case "chat_center" -> AIAgentService.getInstance().pushChat(target, message, ChatType.YELLOW_CENTER);
            case "html" -> AIAgentService.getInstance().pushHtml(target, message);
            default -> {
                respond(ex, 400, "{\"error\":\"Unknown type: " + type + "\"}");
                return;
            }
        }

        respond(ex, 200, "{\"status\":\"ok\",\"pushed_to\":\"" + target.getName() + "\",\"type\":\"" + type + "\"}");
    }

    /**
     * GET /api/ai/players — List online players with basic info for AI targeting.
     */
    private void handleAIPlayers(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Player p : World.getInstance().getAllPlayers()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":%d,\"name\":\"%s\",\"level\":%d,\"class\":\"%s\",\"race\":\"%s\",\"map\":%d,\"x\":%.0f,\"y\":%.0f,\"z\":%.0f}"
                .formatted(
                    p.getObjectId(), p.getName(), p.getLevel(),
                    p.getPlayerClass().name(), p.getRace().name(),
                    p.getWorldId(), p.getX(), p.getY(), p.getZ()));
        }
        sb.append("]");
        respond(ex, 200, sb.toString());
    }

    private Player findPlayerByName(String name) {
        for (Player p : World.getInstance().getAllPlayers()) {
            if (p.getName().equalsIgnoreCase(name))
                return p;
        }
        return null;
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Minimal JSON string value extractor for flat objects.
     */
    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    // --- Helpers ---

    private int getOnlineCount() {
        try {
            return World.getInstance().getAllPlayers().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatUptime() {
        Duration d = Duration.between(START_TIME, Instant.now());
        return "%dd %dh %dm %ds".formatted(d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
