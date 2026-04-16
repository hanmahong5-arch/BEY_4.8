package com.aionemu.gameserver.ai.swarm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure-function heads of NpcAttentionScorer.
 *
 * <p>The 5-head attention scorer has two kinds of heads:
 * <ul>
 *   <li>Pure-function heads (hate, proximity, distress, hpPressure) which take
 *       primitive inputs — these are exhaustively covered here.</li>
 *   <li>Consensus head which requires a live Npc + KnownList; that is covered
 *       by in-game scenario tests (Phase 1 Team C), not by unit tests, because
 *       mocking the whole NPC graph buys nothing over the real game run.</li>
 * </ul>
 *
 * <p>Each head must map its domain to [0, 1]. These tests verify:
 * <ul>
 *   <li>Boundary behaviour (0 input, max input)</li>
 *   <li>Monotonicity where expected</li>
 *   <li>Threshold ramp corners (HP pressure)</li>
 * </ul>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
class NpcAttentionScorerTest {

	private static final float EPS = 1e-4f;

	// -----------------------------------------------------------------------
	// Head 0 — Hate (log-scale, saturating)
	// -----------------------------------------------------------------------

	@Test
	void hate_zero_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.hateValue(0), EPS);
	}

	@Test
	void hate_negative_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.hateValue(-100), EPS);
	}

	@Test
	void hate_atNormalisationCeiling_returnsOne() {
		// 100k is the ceiling, should map to very close to 1
		float v = NpcAttentionScorer.hateValue(100_000);
		assertTrue(v > 0.99f && v <= 1.0f, "100k hate should saturate near 1, got " + v);
	}

	@Test
	void hate_beyondCeiling_clampsToOne() {
		assertEquals(1f, NpcAttentionScorer.hateValue(Integer.MAX_VALUE), EPS);
	}

	@Test
	void hate_monotonicallyIncreasing() {
		float a = NpcAttentionScorer.hateValue(100);
		float b = NpcAttentionScorer.hateValue(1_000);
		float c = NpcAttentionScorer.hateValue(10_000);
		assertTrue(a < b, "hate(100) < hate(1000)");
		assertTrue(b < c, "hate(1000) < hate(10000)");
	}

	@Test
	void hate_logScale_earlyHateGainsMoreThanLate() {
		// log scale property: going 0→1000 should yield more score than 99_000→100_000
		float earlyGain = NpcAttentionScorer.hateValue(1_000) - NpcAttentionScorer.hateValue(0);
		float lateGain  = NpcAttentionScorer.hateValue(100_000) - NpcAttentionScorer.hateValue(99_000);
		assertTrue(earlyGain > lateGain, "Early hate should gain more than late — log scale");
	}

	// -----------------------------------------------------------------------
	// Head 1 — Proximity (linear inverse)
	// -----------------------------------------------------------------------

	@Test
	void proximity_zeroDistance_returnsOne() {
		assertEquals(1f, NpcAttentionScorer.proximityValue(0f), EPS);
	}

	@Test
	void proximity_atMaxRange_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.proximityValue(50f), EPS);
	}

	@Test
	void proximity_beyondMaxRange_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.proximityValue(500f), EPS);
	}

	@Test
	void proximity_halfway_returnsHalf() {
		assertEquals(0.5f, NpcAttentionScorer.proximityValue(25f), EPS);
	}

	@Test
	void proximity_monotonicallyDecreasing() {
		float a = NpcAttentionScorer.proximityValue(5f);
		float b = NpcAttentionScorer.proximityValue(15f);
		float c = NpcAttentionScorer.proximityValue(30f);
		assertTrue(a > b && b > c, "Proximity must decrease with distance");
	}

	// -----------------------------------------------------------------------
	// Head 2 — Distress (pheromone saturation)
	// -----------------------------------------------------------------------

	@Test
	void distress_zero_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.distressValue(0), EPS);
	}

	@Test
	void distress_atDeathDeposit_returnsOne() {
		assertEquals(1f, NpcAttentionScorer.distressValue(SwarmPheromoneGrid.DEPOSIT_DEATH), EPS);
	}

	@Test
	void distress_beyondDeathDeposit_clampsToOne() {
		assertEquals(1f, NpcAttentionScorer.distressValue(SwarmPheromoneGrid.DEPOSIT_DEATH * 5), EPS);
	}

	@Test
	void distress_halfDeath_returnsHalf() {
		assertEquals(0.5f, NpcAttentionScorer.distressValue(SwarmPheromoneGrid.DEPOSIT_DEATH / 2), EPS);
	}

	// -----------------------------------------------------------------------
	// Head 4 — HP Pressure (two-corner ramp)
	// -----------------------------------------------------------------------

	@Test
	void hpPressure_veryLowHp_returnsOne() {
		assertEquals(1f, NpcAttentionScorer.hpPressureValue(0.01f), EPS);
	}

	@Test
	void hpPressure_atLowerCorner_returnsOne() {
		// 30% HP — inclusive lower corner
		assertEquals(1f, NpcAttentionScorer.hpPressureValue(0.30f), EPS);
	}

	@Test
	void hpPressure_atUpperCorner_returnsZero() {
		// 60% HP — exclusive upper corner
		assertEquals(0f, NpcAttentionScorer.hpPressureValue(0.60f), EPS);
	}

	@Test
	void hpPressure_aboveUpperCorner_returnsZero() {
		assertEquals(0f, NpcAttentionScorer.hpPressureValue(0.85f), EPS);
		assertEquals(0f, NpcAttentionScorer.hpPressureValue(1.00f), EPS);
	}

	@Test
	void hpPressure_halfwayBetweenCorners_returnsHalf() {
		// 45% HP is halfway between 30% (score=1) and 60% (score=0) → score=0.5
		assertEquals(0.5f, NpcAttentionScorer.hpPressureValue(0.45f), EPS);
	}

	@Test
	void hpPressure_linearRamp_monotonic() {
		// In the ramp region [30%, 60%] the function must be strictly decreasing
		float a = NpcAttentionScorer.hpPressureValue(0.35f);
		float b = NpcAttentionScorer.hpPressureValue(0.45f);
		float c = NpcAttentionScorer.hpPressureValue(0.55f);
		assertTrue(a > b && b > c, "HP pressure must decrease through ramp region");
	}

	// -----------------------------------------------------------------------
	// Sanity: all heads return values in [0, 1]
	// -----------------------------------------------------------------------

	@Test
	void allHeads_alwaysReturnInUnitRange() {
		int[] hateSamples = {0, 1, 100, 10_000, 100_000, 1_000_000};
		float[] distSamples = {0f, 1f, 10f, 25f, 50f, 100f, 1000f};
		int[] phSamples = {0, 50, 300, 600, 1200, 5000};
		float[] hpSamples = {0f, 0.1f, 0.3f, 0.45f, 0.6f, 0.9f, 1.0f};

		for (int v : hateSamples)      assertInUnitRange("hate(" + v + ")", NpcAttentionScorer.hateValue(v));
		for (float v : distSamples)    assertInUnitRange("proximity(" + v + ")", NpcAttentionScorer.proximityValue(v));
		for (int v : phSamples)        assertInUnitRange("distress(" + v + ")", NpcAttentionScorer.distressValue(v));
		for (float v : hpSamples)      assertInUnitRange("hpPressure(" + v + ")", NpcAttentionScorer.hpPressureValue(v));
	}

	private static void assertInUnitRange(String label, float v) {
		assertTrue(v >= 0f && v <= 1f, label + " = " + v + " outside [0,1]");
	}
}
