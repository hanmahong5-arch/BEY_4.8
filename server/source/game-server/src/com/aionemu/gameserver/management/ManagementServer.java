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

import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AIAgentService;
import com.aionemu.gameserver.services.HTMLService;
import com.aionemu.gameserver.services.siege.SoloFortressService;
import com.aionemu.gameserver.services.pvpseason.PvpSeasonService;
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
            // Custom feature observability endpoints
            server.createContext("/api/metrics", this::handleMetrics);
            server.createContext("/api/audit/tail", this::handleAuditTail);
            server.createContext("/api/fortress/leaderboard", this::handleFortressLeaderboard);
            server.createContext("/api/season/leaderboard", this::handleSeasonLeaderboard);
            server.createContext("/api/dashboard", this::handleDashboard);

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

    private void respondHtml(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // --- Custom feature handlers ---

    /** GET /api/metrics — snapshot of CustomFeatureMetrics counters + timings. */
    private void handleMetrics(HttpExchange ex) throws IOException {
        Map<String, Long> counters = CustomFeatureMetrics.getInstance().snapshot();
        Map<String, String> timings = CustomFeatureMetrics.getInstance().timingSnapshot();
        StringBuilder sb = new StringBuilder("{\n  \"counters\": {");
        int i = 0;
        for (var e : counters.entrySet()) {
            if (i > 0) sb.append(",");
            sb.append("\n    \"").append(escapeJson(e.getKey())).append("\": ").append(e.getValue());
            i++;
        }
        sb.append("\n  },\n  \"timings\": {");
        i = 0;
        for (var e : timings.entrySet()) {
            if (i > 0) sb.append(",");
            sb.append("\n    \"").append(escapeJson(e.getKey())).append("\": \"")
                .append(escapeJson(e.getValue())).append("\"");
            i++;
        }
        sb.append("\n  }\n}");
        respond(ex, 200, sb.toString());
    }

    /** GET /api/audit/tail?feature=fortress&n=20 — audit log tail. */
    private void handleAuditTail(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String feature = null;
        int n = 20;
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    if ("feature".equals(kv[0])) feature = kv[1];
                    else if ("n".equals(kv[0])) {
                        try { n = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        n = Math.min(Math.max(1, n), 200);
        List<String> lines = CustomAuditLog.getInstance().tail(n, feature);
        StringBuilder sb = new StringBuilder("{\n  \"feature\": ");
        sb.append(feature == null ? "null" : "\"" + feature + "\"");
        sb.append(",\n  \"count\": ").append(lines.size()).append(",\n  \"entries\": [");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n    ").append(lines.get(i));
        }
        sb.append("\n  ]\n}");
        respond(ex, 200, sb.toString());
    }

    /** GET /api/fortress/leaderboard — lord leaderboard sorted by fortress count. */
    private void handleFortressLeaderboard(HttpExchange ex) throws IOException {
        List<Object[]> lb = SoloFortressService.getInstance().getLeaderboard();
        StringBuilder sb = new StringBuilder("{\n  \"count\": ").append(lb.size());
        sb.append(",\n  \"ranking\": [");
        for (int i = 0; i < lb.size(); i++) {
            Object[] entry = lb.get(i);
            if (i > 0) sb.append(",");
            sb.append("\n    {\"rank\":").append(i + 1)
                .append(",\"name\":\"").append(escapeJson((String) entry[0])).append("\"")
                .append(",\"fortresses\":").append(entry[1])
                .append(",\"names\":\"").append(escapeJson((String) entry[2])).append("\"}");
        }
        sb.append("\n  ]\n}");
        respond(ex, 200, sb.toString());
    }

    /** GET /api/season/leaderboard?n=20 — PvP Season leaderboard. */
    private void handleSeasonLeaderboard(HttpExchange ex) throws IOException {
        int n = 20;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "n".equals(kv[0])) {
                    try { n = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
                }
            }
        }
        n = Math.min(Math.max(1, n), 100);
        List<Object[]> lb = PvpSeasonService.getInstance().buildLeaderboard(n);
        Map<String, Object> snap = PvpSeasonService.getInstance().snapshot();
        StringBuilder sb = new StringBuilder("{\n  \"season\": {");
        int i = 0;
        for (var e : snap.entrySet()) {
            if (i > 0) sb.append(",");
            Object v = e.getValue();
            sb.append("\n    \"").append(escapeJson(e.getKey())).append("\": ");
            if (v instanceof Number) sb.append(v);
            else sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
            i++;
        }
        sb.append("\n  },\n  \"ranking\": [");
        for (int k = 0; k < lb.size(); k++) {
            Object[] entry = lb.get(k);
            if (k > 0) sb.append(",");
            sb.append("\n    {\"rank\":").append(k + 1)
                .append(",\"player_id\":").append(entry[0])
                .append(",\"name\":\"").append(escapeJson(String.valueOf(entry[1]))).append("\"")
                .append(",\"kills\":").append(entry[2])
                .append(",\"deaths\":").append(entry[3])
                .append(",\"ap_earned\":").append(entry[4])
                .append("}");
        }
        sb.append("\n  ]\n}");
        respond(ex, 200, sb.toString());
    }

    /** GET /api/dashboard — HTML dashboard with live metrics + leaderboards. */
    private void handleDashboard(HttpExchange ex) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        h.append("<title>BEY_4.8 Dashboard</title>");
        h.append("<style>");
        h.append("body{font-family:'Consolas',monospace;background:#0d1117;color:#c9d1d9;padding:20px;}");
        h.append("h1{color:#f0883e;border-bottom:2px solid #30363d;}");
        h.append("h2{color:#58a6ff;margin-top:30px;}");
        h.append("table{border-collapse:collapse;margin:10px 0;}");
        h.append("th,td{padding:6px 12px;border:1px solid #30363d;text-align:left;}");
        h.append("th{background:#161b22;color:#f0883e;}");
        h.append("tr:nth-child(even){background:#161b22;}");
        h.append(".rank-1{color:#ffd700;font-weight:bold;}");
        h.append(".rank-2{color:#c0c0c0;font-weight:bold;}");
        h.append(".rank-3{color:#cd7f32;font-weight:bold;}");
        h.append(".k{color:#58a6ff;}.v{color:#7ee787;}");
        h.append("</style></head><body>");
        h.append("<h1>BEY_4.8 自定义特性仪表盘</h1>");
        h.append("<p>Live as of ").append(Instant.now()).append(" — Uptime: ").append(formatUptime()).append("</p>");

        // Fortress leaderboard
        h.append("<h2>要塞领主排行榜</h2>");
        h.append("<table><tr><th>#</th><th>领主</th><th>要塞数</th><th>名录</th></tr>");
        List<Object[]> fLb = SoloFortressService.getInstance().getLeaderboard();
        int rank = 1;
        for (Object[] entry : fLb) {
            String cls = rank <= 3 ? " class=\"rank-" + rank + "\"" : "";
            h.append("<tr").append(cls).append("><td>").append(rank++).append("</td>")
                .append("<td>").append(htmlEscape((String) entry[0])).append("</td>")
                .append("<td>").append(entry[1]).append("</td>")
                .append("<td>").append(htmlEscape((String) entry[2])).append("</td></tr>");
            if (rank > 10) break;
        }
        if (fLb.isEmpty()) h.append("<tr><td colspan=4>(无)</td></tr>");
        h.append("</table>");

        // PvP Season leaderboard
        h.append("<h2>PvP 赛季排行榜</h2>");
        Map<String, Object> seasonSnap = PvpSeasonService.getInstance().snapshot();
        h.append("<p>");
        for (var e : seasonSnap.entrySet()) {
            h.append("<span class=\"k\">").append(e.getKey()).append("</span>=")
                .append("<span class=\"v\">").append(e.getValue()).append("</span> &nbsp; ");
        }
        h.append("</p>");
        h.append("<table><tr><th>#</th><th>玩家</th><th>击杀</th><th>死亡</th><th>AP</th></tr>");
        List<Object[]> sLb = PvpSeasonService.getInstance().buildLeaderboard(20);
        rank = 1;
        for (Object[] entry : sLb) {
            String cls = rank <= 3 ? " class=\"rank-" + rank + "\"" : "";
            h.append("<tr").append(cls).append("><td>").append(rank++).append("</td>")
                .append("<td>").append(htmlEscape((String) entry[1])).append("</td>")
                .append("<td>").append(entry[2]).append("</td>")
                .append("<td>").append(entry[3]).append("</td>")
                .append("<td>").append(entry[4]).append("</td></tr>");
        }
        if (sLb.isEmpty()) h.append("<tr><td colspan=5>(无)</td></tr>");
        h.append("</table>");

        // Metrics
        h.append("<h2>特性指标</h2><table><tr><th>Key</th><th>Value</th></tr>");
        Map<String, Long> counters = CustomFeatureMetrics.getInstance().snapshot();
        for (var e : counters.entrySet()) {
            h.append("<tr><td class=\"k\">").append(e.getKey()).append("</td><td class=\"v\">")
                .append(e.getValue()).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<p style=\"margin-top:40px;color:#666;\">JSON endpoints: ")
            .append("<a href=\"/api/metrics\" style=\"color:#58a6ff\">/api/metrics</a> | ")
            .append("<a href=\"/api/fortress/leaderboard\" style=\"color:#58a6ff\">/api/fortress/leaderboard</a> | ")
            .append("<a href=\"/api/season/leaderboard\" style=\"color:#58a6ff\">/api/season/leaderboard</a> | ")
            .append("<a href=\"/api/audit/tail?n=20\" style=\"color:#58a6ff\">/api/audit/tail?n=20</a></p>");
        h.append("</body></html>");
        respondHtml(ex, 200, h.toString());
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
