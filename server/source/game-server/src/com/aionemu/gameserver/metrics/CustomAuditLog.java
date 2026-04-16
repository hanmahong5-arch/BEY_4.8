package com.aionemu.gameserver.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only JSONL audit trail for custom feature events and GM commands.
 *
 * <p>Each event is one JSON line written to {@code log/custom_audit.jsonl}.
 * Fields: ts (epoch ms), feature, action, actor, detail.
 *
 * <p>Used by:
 * <ul>
 *   <li>Solo Fortress — capture / dethrone / decay / tax / GM reset / GM grant</li>
 *   <li>FFA — enter / exit / death / kill streak / GM clear</li>
 *   <li>Admin commands — all destructive operations</li>
 * </ul>
 *
 * <p>Queryable via {@link #tail(int, String)} for {@code //fortress history}
 * and {@code //ffa history} admin commands.
 *
 * <p><b>Thread safety</b>: synchronized on write lock; never blocks game threads
 * for more than a single file append. IO failures are swallowed and logged —
 * audit loss never crashes the server.
 *
 * @author BEY_4.8 7-dim evolution
 */
public final class CustomAuditLog {

	private static final CustomAuditLog INSTANCE = new CustomAuditLog();
	private static final Logger log = LoggerFactory.getLogger(CustomAuditLog.class);

	private final Path logFile;
	private final Object writeLock = new Object();

	private CustomAuditLog() {
		logFile = Paths.get("log", "custom_audit.jsonl");
		try {
			Files.createDirectories(logFile.getParent());
		} catch (IOException e) {
			log.warn("[Audit] failed to create log directory", e);
		}
	}

	public static CustomAuditLog getInstance() {
		return INSTANCE;
	}

	/**
	 * Append one audit event. All parameters are null-safe.
	 *
	 * @param feature subsystem name (fortress, ffa, gm)
	 * @param action  what happened (capture, dethrone, enter, clear, etc.)
	 * @param actor   who triggered it (player name or GM name)
	 * @param detail  free-text context (fortress id, target name, etc.)
	 */
	public void log(String feature, String action, String actor, String detail) {
		String line = "{\"ts\":" + System.currentTimeMillis()
			+ ",\"feature\":\"" + esc(feature)
			+ "\",\"action\":\"" + esc(action)
			+ "\",\"actor\":\"" + esc(actor)
			+ "\",\"detail\":\"" + esc(detail)
			+ "\"}\n";
		synchronized (writeLock) {
			try {
				Files.write(logFile, line.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				log.warn("[Audit] write failed: {}", e.getMessage());
			}
		}
	}

	/** Shorthand for GM command audit. */
	public void logGm(String command, String gmName, String detail) {
		log("gm", command, gmName, detail);
	}

	/**
	 * Read the last N lines, optionally filtered by feature prefix.
	 * Returns newest-first. Used by {@code //fortress history} etc.
	 *
	 * @param maxLines     max entries to return
	 * @param featureFilter if non-null, only lines containing this feature value
	 * @return list of raw JSON lines (newest first), at most maxLines
	 */
	public List<String> tail(int maxLines, String featureFilter) {
		if (maxLines <= 0)
			return Collections.emptyList();
		try {
			if (!Files.exists(logFile))
				return Collections.emptyList();
			List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
			List<String> result = new ArrayList<>();
			// Walk backwards for newest-first
			for (int i = allLines.size() - 1; i >= 0 && result.size() < maxLines; i--) {
				String line = allLines.get(i);
				if (line.isEmpty())
					continue;
				if (featureFilter != null && !line.contains("\"feature\":\"" + featureFilter + "\""))
					continue;
				result.add(line);
			}
			return result;
		} catch (IOException e) {
			log.warn("[Audit] tail read failed", e);
			return Collections.emptyList();
		}
	}

	/** JSON-escape a string (backslash + double-quote only). */
	private static String esc(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}
}
