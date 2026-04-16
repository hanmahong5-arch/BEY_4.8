package com.aionemu.gameserver.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight per-feature counter registry for custom game features.
 *
 * <p>Fills the gap left by {@link WorldPulse}, which is hard-wired to
 * PvE/PvP/map-heat semantics and persists to the {@code world_pulse} table.
 * This class is a pure in-memory, fire-and-forget counter bag — zero DB
 * traffic, zero scheduling, O(1) per increment. Designed to be called from
 * hot paths (NPC death, player death, skill cast) without measurable cost.
 *
 * <p><b>Naming convention</b>: dot-delimited lowercase keys, grouped by
 * feature prefix:
 * <pre>
 *   ffa.toggle.enter
 *   ffa.toggle.exit
 *   ffa.death
 *   ffa.chest.spawned
 *   fortress.solo.capture
 *   fortress.solo.dethrone
 *   fortress.solo.bounty.awarded
 *   fortress.solo.bounty.denied_cooldown
 *   fortress.solo.tax.paid
 *   fortress.solo.tax.kinah_total
 *   fortress.solo.decay
 *   fortress.solo.lord_buff.applied
 *   npc.hardcore.bonus_equip_drops
 *   npc.hardcore.bonus_sell_drops
 * </pre>
 *
 * <p><b>Thread safety</b>: backed by {@link ConcurrentHashMap} +
 * {@link AtomicLong}. Safe to call from any thread; never blocks.
 *
 * <p><b>Exposure</b>: queried by {@code //fortress status} and
 * {@code //ffa status} admin commands. Not persisted — counters reset on
 * server restart, which is acceptable since the audit trail lives in the
 * server_console.log under each feature's logger.
 *
 * @author BEY_4.8 industrial hardening
 */
public final class CustomFeatureMetrics {

	private static final CustomFeatureMetrics INSTANCE = new CustomFeatureMetrics();
	private static final Logger log = LoggerFactory.getLogger(CustomFeatureMetrics.class);

	private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

	/** Lightweight timing stats: min/max/count/sum per key. */
	private final ConcurrentMap<String, long[]> timings = new ConcurrentHashMap<>();

	/** Whether the JVM shutdown hook has been registered. */
	private volatile boolean shutdownHookRegistered;

	private CustomFeatureMetrics() {}

	public static CustomFeatureMetrics getInstance() {
		return INSTANCE;
	}

	/** Increment a counter by 1. Creates the counter if it does not exist. */
	public void inc(String key) {
		add(key, 1L);
	}

	/** Add a delta to a counter. Negative deltas subtract; zero is a no-op. */
	public void add(String key, long delta) {
		if (key == null || delta == 0)
			return;
		counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
	}

	/** Peek at a counter's current value; returns 0 if unseen. */
	public long get(String key) {
		AtomicLong c = counters.get(key);
		return c == null ? 0L : c.get();
	}

	/** Reset a single counter to zero (used by ops tooling). */
	public void reset(String key) {
		AtomicLong c = counters.get(key);
		if (c != null)
			c.set(0L);
	}

	/** Reset every counter. Mainly for test harnesses and emergency ops. */
	public void resetAll() {
		counters.values().forEach(c -> c.set(0L));
	}

	/**
	 * Ordered snapshot of all counters at this instant. The returned map is a
	 * fresh copy — caller may mutate freely without affecting live counters.
	 * Sorted alphabetically for stable admin-console output.
	 */
	public Map<String, Long> snapshot() {
		Map<String, Long> out = new TreeMap<>();
		counters.forEach((k, v) -> out.put(k, v.get()));
		return out;
	}

	// ────────────── Timing (Observability dimension) ──────────────

	/**
	 * Record a duration sample. Maintains min/max/count/sum per key.
	 * Thread-safe via synchronized on the per-key array.
	 *
	 * @param key        metric name (e.g. "fortress.solo.sweep_ms")
	 * @param durationMs elapsed milliseconds
	 */
	public void recordTiming(String key, long durationMs) {
		if (key == null)
			return;
		// long[0]=min, long[1]=max, long[2]=count, long[3]=sum
		long[] stats = timings.computeIfAbsent(key, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
		synchronized (stats) {
			if (durationMs < stats[0]) stats[0] = durationMs;
			if (durationMs > stats[1]) stats[1] = durationMs;
			stats[2]++;
			stats[3] += durationMs;
		}
	}

	/** Snapshot of timing stats: key → "min/max/avg/count" formatted string. */
	public Map<String, String> timingSnapshot() {
		Map<String, String> out = new TreeMap<>();
		timings.forEach((k, v) -> {
			synchronized (v) {
				if (v[2] == 0)
					return;
				long avg = v[3] / v[2];
				out.put(k, "min=" + v[0] + " max=" + v[1] + " avg=" + avg + " n=" + v[2]);
			}
		});
		return out;
	}

	// ────────────── Persistence (Reliability dimension) ──────────────

	/**
	 * Save all counter values to a JSON file. Called on JVM shutdown.
	 * Format: one key=value per line, simple enough to parse without Jackson.
	 */
	public void saveToFile(Path path) {
		StringBuilder sb = new StringBuilder("{\n");
		Map<String, Long> snap = snapshot();
		int i = 0;
		for (Map.Entry<String, Long> e : snap.entrySet()) {
			sb.append("  \"").append(e.getKey()).append("\": ").append(e.getValue());
			if (++i < snap.size())
				sb.append(',');
			sb.append('\n');
		}
		sb.append("}\n");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
			log.info("[Metrics] saved {} counters to {}", snap.size(), path);
		} catch (IOException e) {
			log.warn("[Metrics] save failed: {}", e.getMessage());
		}
	}

	/**
	 * Load counter values from a previously saved JSON file. Additive:
	 * loaded values are added to current counters (not replacing).
	 * Called once during feature init to restore metrics across restarts.
	 */
	public void loadFromFile(Path path) {
		if (!Files.exists(path))
			return;
		try {
			String content = Files.readString(path, StandardCharsets.UTF_8);
			// Minimal JSON parse: find "key": value patterns
			int loaded = 0;
			for (String line : content.split("\n")) {
				line = line.trim();
				if (line.startsWith("{") || line.startsWith("}"))
					continue;
				int q1 = line.indexOf('"');
				int q2 = line.indexOf('"', q1 + 1);
				int colon = line.indexOf(':', q2 + 1);
				if (q1 < 0 || q2 < 0 || colon < 0)
					continue;
				String key = line.substring(q1 + 1, q2);
				String valStr = line.substring(colon + 1).trim().replace(",", "");
				try {
					long val = Long.parseLong(valStr);
					add(key, val);
					loaded++;
				} catch (NumberFormatException ignored) {
					// skip malformed
				}
			}
			log.info("[Metrics] restored {} counters from {}", loaded, path);
		} catch (IOException e) {
			log.warn("[Metrics] load failed: {}", e.getMessage());
		}
	}

	/**
	 * Register a JVM shutdown hook to persist metrics. Idempotent.
	 * Call once from feature initialization (e.g. SoloFortressService.init).
	 */
	public void registerShutdownPersistence(Path savePath) {
		if (shutdownHookRegistered)
			return;
		shutdownHookRegistered = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> saveToFile(savePath), "MetricsPersist"));
		log.info("[Metrics] shutdown persistence registered → {}", savePath);
	}
}
