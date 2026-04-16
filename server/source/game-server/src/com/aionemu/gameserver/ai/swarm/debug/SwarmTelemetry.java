package com.aionemu.gameserver.ai.swarm.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.CustomConfig;

/**
 * In-memory telemetry bus for the swarm-intelligence subsystem.
 *
 * <p>Everything here is a <em>no-op</em> when {@link CustomConfig#SWARM_DEBUG_ENABLED}
 * is false — one volatile-read + branch at each record site and nothing else.
 * When enabled, it collects lightweight counters, a timing reservoir for
 * percentile calculation, and a ring-buffer of recent target-switch decisions
 * that {@code //swarm} admin commands can read back.
 *
 * <p>A dedicated logback logger {@code SWARM_DECISION_LOG} also persists each
 * decision as CSV to {@code log/swarm_decisions.log} when debug is enabled.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class SwarmTelemetry {

	/** Dedicated slf4j logger — wired in logback.xml to a rolling file appender. */
	private static final Logger DECISION_LOG = LoggerFactory.getLogger("SWARM_DECISION_LOG");

	/** Power-of-two ring sizes for cheap `& (size-1)` modulo. */
	private static final int TIMING_RESERVOIR = 1024;
	private static final int DECISION_BUFFER  = 256;

	private static final SwarmTelemetry INSTANCE = new SwarmTelemetry();
	public static SwarmTelemetry getInstance() { return INSTANCE; }

	// --- counters (cheap AtomicLong increments) ---
	private final AtomicLong thinkAttackCalls   = new AtomicLong();
	private final AtomicLong reevaluateCalls    = new AtomicLong();
	private final AtomicLong targetSwitches     = new AtomicLong();
	private final AtomicLong investigateCalls   = new AtomicLong();
	private final AtomicLong investigateHits    = new AtomicLong();
	private final AtomicLong pheromoneDeposits  = new AtomicLong();
	private final AtomicLong distressBroadcasts = new AtomicLong();
	private final AtomicLong disengageSuppressed = new AtomicLong();
	private final AtomicLong returnSuppressed    = new AtomicLong();

	// --- timing reservoir for p50/p99 of thinkAttack (nanoseconds) ---
	private final long[] thinkAttackNanos = new long[TIMING_RESERVOIR];
	private final AtomicLong timingIdx = new AtomicLong();

	// --- ring buffer of recent target-switch decisions ---
	private final Decision[] decisions = new Decision[DECISION_BUFFER];
	private final AtomicLong decisionIdx = new AtomicLong();

	private SwarmTelemetry() {}

	// -----------------------------------------------------------------------
	// Record methods — all no-op when debug is off
	// -----------------------------------------------------------------------

	public void recordThinkAttackTime(long nanos) {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		thinkAttackCalls.incrementAndGet();
		int i = (int) (timingIdx.getAndIncrement() & (TIMING_RESERVOIR - 1));
		thinkAttackNanos[i] = nanos;
	}

	public void recordReevaluate(boolean switched) {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		reevaluateCalls.incrementAndGet();
		if (switched) targetSwitches.incrementAndGet();
	}

	public void recordInvestigation(boolean hit) {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		investigateCalls.incrementAndGet();
		if (hit) investigateHits.incrementAndGet();
	}

	public void recordDeposit() {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		pheromoneDeposits.incrementAndGet();
	}

	public void recordDistressBroadcast() {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		distressBroadcasts.incrementAndGet();
	}

	public void recordDisengageSuppressed() {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		disengageSuppressed.incrementAndGet();
	}

	public void recordReturnSuppressed() {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		returnSuppressed.incrementAndGet();
	}

	public void recordDecision(int npcId, String npcName, String oldTargetName, String newTargetName,
	                           float oldScore, float newScore) {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) return;
		Decision d = new Decision(System.currentTimeMillis(), npcId, npcName,
			oldTargetName == null ? "<none>" : oldTargetName,
			newTargetName == null ? "<none>" : newTargetName,
			oldScore, newScore);
		int i = (int) (decisionIdx.getAndIncrement() & (DECISION_BUFFER - 1));
		decisions[i] = d;
		DECISION_LOG.info("{},{},{},{},{},{},{}",
			d.ts, d.npcId, d.npcName, d.oldTarget, d.newTarget,
			String.format("%.4f", d.oldScore),
			String.format("%.4f", d.newScore));
	}

	// -----------------------------------------------------------------------
	// Query methods — called by //swarm admin command
	// -----------------------------------------------------------------------

	/** Immutable snapshot of all counters + p50/p99 thinkAttack timing. */
	public Snapshot snapshot() {
		long[] copy = thinkAttackNanos.clone();
		Arrays.sort(copy);
		// skip leading zeros from unfilled slots
		int filled = 0;
		for (long v : copy) if (v > 0) filled++;
		long p50 = 0, p99 = 0;
		if (filled > 0) {
			int baseOffset = copy.length - filled;
			p50 = copy[baseOffset + (int) (filled * 0.50)];
			p99 = copy[baseOffset + Math.min(filled - 1, (int) (filled * 0.99))];
		}
		return new Snapshot(
			thinkAttackCalls.get(),
			reevaluateCalls.get(),
			targetSwitches.get(),
			investigateCalls.get(),
			investigateHits.get(),
			pheromoneDeposits.get(),
			distressBroadcasts.get(),
			disengageSuppressed.get(),
			returnSuppressed.get(),
			p50, p99
		);
	}

	/** Returns the last {@code limit} decisions, newest first. */
	public List<Decision> recentDecisions(int limit) {
		int cap = Math.min(limit, DECISION_BUFFER);
		List<Decision> out = new ArrayList<>(cap);
		long cur = decisionIdx.get();
		for (int k = 0; k < cap; k++) {
			long readIdx = cur - 1 - k;
			if (readIdx < 0) break;
			Decision d = decisions[(int) (readIdx & (DECISION_BUFFER - 1))];
			if (d == null) break;
			out.add(d);
		}
		return out;
	}

	/** Resets all counters — useful to isolate a test window. */
	public void reset() {
		thinkAttackCalls.set(0);
		reevaluateCalls.set(0);
		targetSwitches.set(0);
		investigateCalls.set(0);
		investigateHits.set(0);
		pheromoneDeposits.set(0);
		distressBroadcasts.set(0);
		disengageSuppressed.set(0);
		returnSuppressed.set(0);
		Arrays.fill(thinkAttackNanos, 0L);
		Arrays.fill(decisions, null);
		timingIdx.set(0);
		decisionIdx.set(0);
	}

	// -----------------------------------------------------------------------
	// DTOs
	// -----------------------------------------------------------------------

	public record Snapshot(
		long thinkAttackCalls,
		long reevaluateCalls,
		long targetSwitches,
		long investigateCalls,
		long investigateHits,
		long pheromoneDeposits,
		long distressBroadcasts,
		long disengageSuppressed,
		long returnSuppressed,
		long thinkAttackP50Ns,
		long thinkAttackP99Ns
	) {}

	public record Decision(
		long ts,
		int npcId,
		String npcName,
		String oldTarget,
		String newTarget,
		float oldScore,
		float newScore
	) {}
}
