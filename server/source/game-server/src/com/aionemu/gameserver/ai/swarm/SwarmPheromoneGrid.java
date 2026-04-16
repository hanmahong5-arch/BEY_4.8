package com.aionemu.gameserver.ai.swarm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lock-free spatial pheromone grid — stigmergy substrate for swarm intelligence.
 *
 * <h3>Cell layout</h3>
 * Each 8×8 world-unit cell is an AtomicLong:
 * <pre>
 *   bits 63–16  intensity  (48-bit, saturating at MAX_INTENSITY = 0xFFFF_FFFF_FFFFL)
 *   bits 15– 0  reserved for future tribe fingerprint
 * </pre>
 * All operations are CAS — zero lock contention with game threads.
 *
 * <h3>Decay</h3>
 * Exponential decay with 60-second half-life, ticked by a single daemon thread.
 * Dead cells (intensity == 0) are evicted to keep the map compact.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class SwarmPheromoneGrid {

	private static final Logger log = LoggerFactory.getLogger(SwarmPheromoneGrid.class);

	/** World-unit side length of one pheromone bucket. Power of 2 for fast division. */
	public static final int CELL_SIZE = 8;

	// --- deposit magnitudes ---
	/** Pheromone deposited when an NPC first takes a hit (combat entered). */
	public static final int DEPOSIT_COMBAT = 400;
	/** Pheromone deposited on NPC death — strong, long-lasting signal. */
	public static final int DEPOSIT_DEATH  = 1200;

	// --- thresholds ---
	// Tuning note: a single NPC entering combat deposits DEPOSIT_COMBAT = 400.
	// All three thresholds must be ≤ 400 so solo encounters trigger swarm
	// protections, not just multi-NPC pile-ons. Death (1200) always triggers all.
	/** Idle NPCs notice pheromone above this value and begin investigation. */
	public static final int THRESHOLD_ALERT   = 200;
	/** NPCs returning home abort the retreat if pheromone exceeds this. */
	public static final int THRESHOLD_RETURN  = 300;
	/** NPCs refuse to disengage when local pheromone exceeds this. */
	public static final int THRESHOLD_PERSIST = 350;

	private static final long MAX_INTENSITY  = 0xFFFF_FFFFL;
	private static final double HALF_LIFE_S  = 60.0;
	private static final double DECAY_FACTOR = Math.pow(0.5, 1.0 / HALF_LIFE_S);

	// --- singleton ---
	private static final SwarmPheromoneGrid INSTANCE = new SwarmPheromoneGrid();

	private final ConcurrentHashMap<Long, AtomicLong> grid = new ConcurrentHashMap<>(2048);
	private final ScheduledExecutorService decay;

	private SwarmPheromoneGrid() {
		decay = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "SwarmPheromoneDecay");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		decay.scheduleAtFixedRate(this::decayAll, 1, 1, TimeUnit.SECONDS);
		log.info("SwarmPheromoneGrid initialized (halfLife=60s, cellSize={})", CELL_SIZE);
	}

	public static SwarmPheromoneGrid getInstance() { return INSTANCE; }

	// -----------------------------------------------------------------------
	// Public API
	// -----------------------------------------------------------------------

	/** Deposit intensity at a world position. Thread-safe. */
	public void deposit(float x, float y, int mapId, int intensity) {
		long key = cellKey(x, y, mapId);
		grid.compute(key, (k, cell) -> {
			if (cell == null) cell = new AtomicLong(0L);
			cell.updateAndGet(v -> Math.min(v + intensity, MAX_INTENSITY));
			return cell;
		});
	}

	/** Sample raw intensity at world position (0 if cell absent). */
	public int sample(float x, float y, int mapId) {
		AtomicLong cell = grid.get(cellKey(x, y, mapId));
		return cell == null ? 0 : (int) Math.min(cell.get(), Integer.MAX_VALUE);
	}

	/** True when intensity ≥ {@link #THRESHOLD_ALERT} — idle NPCs should investigate. */
	public boolean isAlerted(float x, float y, int mapId) {
		return sample(x, y, mapId) >= THRESHOLD_ALERT;
	}

	/** True when intensity ≥ {@link #THRESHOLD_PERSIST} — engaged NPCs refuse disengage. */
	public boolean isPersistent(float x, float y, int mapId) {
		return sample(x, y, mapId) >= THRESHOLD_PERSIST;
	}

	/** True when intensity ≥ {@link #THRESHOLD_RETURN} — returning NPCs abort retreat. */
	public boolean isReturnSuppressed(float x, float y, int mapId) {
		return sample(x, y, mapId) >= THRESHOLD_RETURN;
	}

	/**
	 * Maximum intensity across all cells within {@code radiusCells} of (x,y).
	 *
	 * <p>Used by disengage-suppression: an NPC that has chased 2-3 cells away from
	 * its initial deposit still sees its own pheromone via this radius lookup,
	 * preventing spurious leash-giveup when the NPC is mid-chase.
	 *
	 * @param radiusCells radius in cells — 2 means a 5×5 area (41 cells)
	 * @return max intensity found, 0 if the area is empty
	 */
	public int maxIntensityNearby(float x, float y, int mapId, int radiusCells) {
		int cx = worldToCell(x);
		int cy = worldToCell(y);
		long mapMask = ((long) mapId & 0xFFFFFFL) << 40;
		int best = 0;
		for (int dx = -radiusCells; dx <= radiusCells; dx++) {
			for (int dy = -radiusCells; dy <= radiusCells; dy++) {
				long key = mapMask | (((long)(cx + dx) & 0xFFFFF) << 20) | ((long)(cy + dy) & 0xFFFFF);
				AtomicLong cell = grid.get(key);
				if (cell == null) continue;
				int v = (int) Math.min(cell.get(), Integer.MAX_VALUE);
				if (v > best) best = v;
			}
		}
		return best;
	}

	/**
	 * Find the world-space centroid of the strongest pheromone cell within
	 * {@code radiusCells} cells of (x,y). Returns {@code null} if no cell
	 * above {@link #THRESHOLD_ALERT} is found.
	 *
	 * <p>Used to give idle NPCs a concrete move target in the direction of
	 * the "threat gradient."
	 *
	 * @return float[]{worldX, worldY} of the strongest nearby cell, or null
	 */
	public float[] findStrongestNearby(float x, float y, int mapId, int radiusCells) {
		int cx = worldToCell(x);
		int cy = worldToCell(y);
		long mapMask = ((long) mapId & 0xFFFFFFL) << 40;

		long bestIntensity = THRESHOLD_ALERT - 1;  // must beat this to qualify
		int bestCx = 0, bestCy = 0;
		boolean found = false;

		for (int dx = -radiusCells; dx <= radiusCells; dx++) {
			for (int dy = -radiusCells; dy <= radiusCells; dy++) {
				if (dx == 0 && dy == 0) continue;
				long key = mapMask | (((long)(cx + dx) & 0xFFFFF) << 20) | ((long)(cy + dy) & 0xFFFFF);
				AtomicLong cell = grid.get(key);
				if (cell == null) continue;
				long v = cell.get();
				if (v > bestIntensity) {
					bestIntensity = v;
					bestCx = cx + dx;
					bestCy = cy + dy;
					found = true;
				}
			}
		}
		if (!found) return null;
		// return cell center in world units
		return new float[]{ bestCx * CELL_SIZE + CELL_SIZE * 0.5f,
		                    bestCy * CELL_SIZE + CELL_SIZE * 0.5f };
	}

	// -----------------------------------------------------------------------
	// Decay
	// -----------------------------------------------------------------------

	private void decayAll() {
		try {
			grid.entrySet().removeIf(e -> {
				long next = e.getValue().updateAndGet(v -> {
					long d = (long) (v * DECAY_FACTOR);
					return d < 1 ? 0L : d;
				});
				return next == 0L;
			});
		} catch (Exception ex) {
			log.warn("Swarm decay error", ex);
		}
	}

	// -----------------------------------------------------------------------
	// Coordinate helpers
	// -----------------------------------------------------------------------

	private static int worldToCell(float w) { return (int) Math.floor(w / CELL_SIZE); }

	private static long cellKey(float x, float y, int mapId) {
		long cx  = worldToCell(x) & 0xFFFFF;
		long cy  = worldToCell(y) & 0xFFFFF;
		long mid = (long) mapId & 0xFFFFFFL;
		return (mid << 40) | (cx << 20) | cy;
	}
}
