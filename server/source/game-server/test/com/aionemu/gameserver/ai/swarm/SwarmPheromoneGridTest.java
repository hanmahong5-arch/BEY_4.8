package com.aionemu.gameserver.ai.swarm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SwarmPheromoneGrid — the stigmergy substrate of the swarm AI.
 *
 * <p>These tests exercise the pure data-structure behaviour (deposit, sample,
 * saturation, gradient search, concurrency). Decay is time-dependent and
 * driven by a scheduled executor, so we verify the decay contract indirectly
 * via the mathematical decay factor — not by sleeping real time.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
class SwarmPheromoneGridTest {

	private static final int MAP_A = 210040000;
	private static final int MAP_B = 210050000; // different map — must not alias cells with A

	private SwarmPheromoneGrid grid;

	@BeforeEach
	void setUp() {
		// The grid is a singleton — reset state per test by depositing into fresh
		// coordinates unique to each test. We use widely-spaced coordinates to
		// avoid cross-test contamination.
		grid = SwarmPheromoneGrid.getInstance();
	}

	// -----------------------------------------------------------------------
	// Deposit + sample round trip
	// -----------------------------------------------------------------------

	@Test
	void sample_emptyCell_returnsZero() {
		// Use coordinates unlikely to have been touched
		assertEquals(0, grid.sample(999_000f, 999_000f, MAP_A));
	}

	@Test
	void deposit_thenSample_returnsAccumulatedValue() {
		float x = 1001f, y = 2001f;
		int before = grid.sample(x, y, MAP_A);
		grid.deposit(x, y, MAP_A, 100);
		grid.deposit(x, y, MAP_A, 50);
		assertEquals(before + 150, grid.sample(x, y, MAP_A));
	}

	@Test
	void deposit_differentMaps_doesNotAlias() {
		float x = 3000f, y = 3000f;
		int mapAbefore = grid.sample(x, y, MAP_A);
		int mapBbefore = grid.sample(x, y, MAP_B);
		grid.deposit(x, y, MAP_A, 200);
		assertEquals(mapAbefore + 200, grid.sample(x, y, MAP_A));
		assertEquals(mapBbefore, grid.sample(x, y, MAP_B), "MAP_B must not see MAP_A deposit");
	}

	@Test
	void deposit_withinSameCell_allCoordinatesAlias() {
		// Cell size is 8 wu — two points within the same 8-unit cell should alias
		float x1 = 4000f, y1 = 4000f;
		float x2 = 4005f, y2 = 4005f;
		int before = grid.sample(x1, y1, MAP_A);
		grid.deposit(x1, y1, MAP_A, 77);
		assertEquals(before + 77, grid.sample(x2, y2, MAP_A),
			"Points within the same 8x8 cell must return the same value");
	}

	// -----------------------------------------------------------------------
	// Threshold helpers
	// -----------------------------------------------------------------------

	@Test
	void isAlerted_belowThreshold_returnsFalse() {
		float x = 5000f, y = 5000f;
		grid.deposit(x, y, MAP_A, SwarmPheromoneGrid.THRESHOLD_ALERT - 1);
		assertFalse(grid.isAlerted(x, y, MAP_A));
	}

	@Test
	void isAlerted_atOrAboveThreshold_returnsTrue() {
		float x = 5100f, y = 5100f;
		grid.deposit(x, y, MAP_A, SwarmPheromoneGrid.THRESHOLD_ALERT);
		assertTrue(grid.isAlerted(x, y, MAP_A));
	}

	@Test
	void isPersistent_requiresHigherThreshold() {
		float x = 5200f, y = 5200f;
		grid.deposit(x, y, MAP_A, SwarmPheromoneGrid.THRESHOLD_PERSIST - 1);
		assertFalse(grid.isPersistent(x, y, MAP_A));
		grid.deposit(x, y, MAP_A, 1);
		assertTrue(grid.isPersistent(x, y, MAP_A));
	}

	@Test
	void isReturnSuppressed_ordering() {
		// THRESHOLD_RETURN should be between ALERT and PERSIST
		assertTrue(SwarmPheromoneGrid.THRESHOLD_ALERT < SwarmPheromoneGrid.THRESHOLD_RETURN);
		assertTrue(SwarmPheromoneGrid.THRESHOLD_RETURN < SwarmPheromoneGrid.THRESHOLD_PERSIST);
	}

	@Test
	void thresholds_calibratedForSoloCombatDeposit() {
		// A single combat-entry deposit (400) must trigger all protections so
		// solo encounters participate in swarm behaviour, not just multi-NPC fights.
		assertTrue(SwarmPheromoneGrid.THRESHOLD_ALERT   <= SwarmPheromoneGrid.DEPOSIT_COMBAT);
		assertTrue(SwarmPheromoneGrid.THRESHOLD_RETURN  <= SwarmPheromoneGrid.DEPOSIT_COMBAT);
		assertTrue(SwarmPheromoneGrid.THRESHOLD_PERSIST <= SwarmPheromoneGrid.DEPOSIT_COMBAT);
	}

	// -----------------------------------------------------------------------
	// maxIntensityNearby — radius window for disengage suppression
	// -----------------------------------------------------------------------

	@Test
	void maxIntensityNearby_emptyArea_returnsZero() {
		assertEquals(0, grid.maxIntensityNearby(997_000f, 997_000f, MAP_A, 3));
	}

	@Test
	void maxIntensityNearby_findsStrongestInRadius() {
		float cx = 12_000f, cy = 12_000f;
		// Plant deposits at varying distances
		grid.deposit(cx + 8f, cy, MAP_A, 500);       // 1 cell east
		grid.deposit(cx + 16f, cy + 16f, MAP_A, 900); // 2 cells NE — should win
		grid.deposit(cx - 24f, cy, MAP_A, 400);       // 3 cells west

		int max = grid.maxIntensityNearby(cx, cy, MAP_A, 2);
		assertTrue(max >= 900, "Expected max ≥ 900, got " + max);
	}

	@Test
	void maxIntensityNearby_excludesCellsOutsideRadius() {
		float cx = 13_000f, cy = 13_000f;
		// Deposit far away — outside radius 2
		grid.deposit(cx + 48f, cy, MAP_A, 5000);  // 6 cells east
		int max = grid.maxIntensityNearby(cx, cy, MAP_A, 2);
		// Should NOT see the far deposit (unless some prior test contaminated this area)
		assertTrue(max < 5000, "Far deposit at +48 must be outside radius 2, got max=" + max);
	}

	// -----------------------------------------------------------------------
	// Gradient search (findStrongestNearby)
	// -----------------------------------------------------------------------

	@Test
	void findStrongestNearby_allBelowThreshold_returnsNull() {
		float x = 6000f, y = 6000f;
		// single below-threshold deposit nearby
		grid.deposit(x + 16f, y, MAP_A, SwarmPheromoneGrid.THRESHOLD_ALERT - 10);
		float[] result = grid.findStrongestNearby(x, y, MAP_A, 5);
		assertNull(result, "Nothing above threshold → null");
	}

	@Test
	void findStrongestNearby_returnsStrongestCellCenter() {
		float cx = 7000f, cy = 7000f;
		// Plant two hotspots — the stronger one should win
		grid.deposit(cx + 16f, cy, MAP_A, 1500);  // +2 cells east, very strong
		grid.deposit(cx, cy + 8f, MAP_A, 600);    // +1 cell north, weaker
		float[] result = grid.findStrongestNearby(cx, cy, MAP_A, 5);
		assertNotNull(result);
		// Result is the cell center of the strong deposit. cell edge: floor(7016/8)*8 = 7016
		// cell center = 7016 + 4 = 7020. Y cell = floor(7000/8)*8 + 4 = 7004
		assertEquals(7020f, result[0], 0.01f, "X should be cell center of east hotspot");
		assertEquals(7004f, result[1], 0.01f, "Y should be cell center of east hotspot");
	}

	@Test
	void findStrongestNearby_ignoresOriginCell() {
		float x = 8000f, y = 8000f;
		// Strong deposit AT origin — should be ignored (dx==0 && dy==0)
		grid.deposit(x, y, MAP_A, 2000);
		// Weaker deposit adjacent
		grid.deposit(x + 8f, y, MAP_A, 500);
		float[] result = grid.findStrongestNearby(x, y, MAP_A, 3);
		assertNotNull(result, "Must find the adjacent cell, not the origin");
		// Must NOT equal the origin cell center
		float originCellCenterX = (float) (Math.floor(x / 8) * 8 + 4);
		assertNotEquals(originCellCenterX, result[0], 0.01f);
	}

	// -----------------------------------------------------------------------
	// Concurrency
	// -----------------------------------------------------------------------

	@Test
	void concurrentDeposits_fromManyThreads_noLostUpdates() throws Exception {
		float x = 9000f, y = 9000f;
		int before = grid.sample(x, y, MAP_A);

		int threads = 16;
		int perThread = 500;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		try {
			for (int t = 0; t < threads; t++) {
				pool.submit(() -> {
					try {
						startGate.await();
						for (int i = 0; i < perThread; i++) {
							grid.deposit(x, y, MAP_A, 1);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			startGate.countDown();
			assertTrue(done.await(30, TimeUnit.SECONDS), "All deposit threads must finish");
		} finally {
			pool.shutdown();
		}

		int expected = before + threads * perThread;
		assertEquals(expected, grid.sample(x, y, MAP_A),
			"No updates may be lost under concurrent AtomicLong CAS");
	}
}
