package com.aionemu.gameserver.ai.swarm;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * 5-head multi-head attention scorer for NPC target prioritisation.
 *
 * <p>Inspired by transformer multi-head attention: each head specialises on a
 * different signal; heads combine linearly into a final priority score ∈ [0,1].
 * The NPC picks the candidate with the highest composite score.
 *
 * <h3>Attention heads and weights</h3>
 * <pre>
 *   Head 0 — Hate accumulation    (w = 0.30)  already-damaged targets stay hot
 *   Head 1 — Proximity            (w = 0.20)  closer = cheaper to pursue
 *   Head 2 — Ally-distress signal (w = 0.20)  pheromone near candidate = allies fought here
 *   Head 3 — Pack consensus       (w = 0.15)  pile-on the target most tribe-mates are hitting
 *   Head 4 — HP pressure          (w = 0.15)  finish low-HP targets — emergent "focus fire"
 * </pre>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class NpcAttentionScorer {

	// weights — must sum to 1.0
	private static final float W_HATE      = 0.30f;
	private static final float W_PROXIMITY = 0.20f;
	private static final float W_DISTRESS  = 0.20f;
	private static final float W_CONSENSUS = 0.15f;
	private static final float W_HP        = 0.15f;

	/** Hate normalisation ceiling (log-scale). */
	private static final float MAX_HATE_LOG = (float) Math.log1p(100_000f);
	/** Proximity normalisation range (world units). */
	private static final float MAX_RANGE    = 50f;
	/** Pack scan radius (world units). */
	private static final int   PACK_RADIUS  = 30;
	/** HP threshold below which the "finish them" head activates fully. */
	private static final float HP_THRESHOLD = 0.30f;

	private NpcAttentionScorer() {}

	/**
	 * Score {@code candidate} as a target for {@code evaluator}.
	 *
	 * @param evaluator   NPC choosing a target
	 * @param candidate   creature being considered
	 * @param hate        evaluator's raw hate for candidate
	 * @param pheromone   pheromone intensity at candidate's position
	 * @return composite score ∈ [0, 1]
	 */
	public static float score(Npc evaluator, Creature candidate, int hate, int pheromone) {
		float h0 = headHate(hate);
		float h1 = headProximity(evaluator, candidate);
		float h2 = headDistress(pheromone);
		float h3 = headConsensus(evaluator, candidate);
		float h4 = headHpPressure(candidate);
		return W_HATE * h0 + W_PROXIMITY * h1 + W_DISTRESS * h2 + W_CONSENSUS * h3 + W_HP * h4;
	}

	/**
	 * Find the highest-scoring target in {@code evaluator}'s aggro list.
	 * Returns {@code null} if the aggro list is empty.
	 *
	 * <p>This is the primary integration point: call it when the NPC is
	 * choosing or re-evaluating a combat target.
	 */
	public static Creature bestTarget(Npc evaluator) {
		SwarmPheromoneGrid grid = SwarmPheromoneGrid.getInstance();
		Creature best = null;
		float bestScore = -1f;
		for (var entry : evaluator.getAggroList().stream().toList()) {
			Creature candidate = entry.getAttacker();
			if (candidate == null || candidate.isDead() || !candidate.isSpawned()) continue;
			int pheromone = grid.sample(candidate.getX(), candidate.getY(), candidate.getWorldId());
			float s = score(evaluator, candidate, entry.getHate(), pheromone);
			if (s > bestScore) { bestScore = s; best = candidate; }
		}
		return best;
	}

	// -----------------------------------------------------------------------
	// Head implementations — each returns [0, 1]
	// Package-private pure functions are exposed for unit testing (Team B).
	// -----------------------------------------------------------------------

	/** Head 0: Hate. Log-scale so high hate doesn't dominate infinitely. */
	static float hateValue(int hate) {
		if (hate <= 0) return 0f;
		return Math.min(1f, (float) Math.log1p(hate) / MAX_HATE_LOG);
	}

	/** Head 1 (pure): Proximity as a function of distance. */
	static float proximityValue(float dist) {
		if (dist <= 0) return 1f;
		return dist >= MAX_RANGE ? 0f : 1f - dist / MAX_RANGE;
	}

	/** Head 2: Ally-distress. Pheromone near the candidate reflects recent combat there. */
	static float distressValue(int pheromone) {
		if (pheromone <= 0) return 0f;
		return Math.min(1f, pheromone / (float) SwarmPheromoneGrid.DEPOSIT_DEATH);
	}

	/** Head 4 (pure): HP-pressure as a function of HP fraction ∈ [0,1]. */
	static float hpPressureValue(float hpFraction) {
		if (hpFraction <= HP_THRESHOLD) return 1f;
		float upper = HP_THRESHOLD * 2; // 0.60
		if (hpFraction >= upper) return 0f;
		return 1f - (hpFraction - HP_THRESHOLD) / (upper - HP_THRESHOLD);
	}

	private static float headHate(int hate) { return hateValue(hate); }

	private static float headProximity(Npc evaluator, Creature candidate) {
		return proximityValue((float) PositionUtil.getDistance(evaluator, candidate, false));
	}

	private static float headDistress(int pheromone) { return distressValue(pheromone); }

	/**
	 * Head 3: Pack consensus.
	 * Fraction of nearby same-tribe allies already hitting this candidate.
	 * O(k) scan capped by known-list radius.
	 */
	private static float headConsensus(Npc evaluator, Creature candidate) {
		int[] counts = {0, 0}; // [tribe peers in range, those hating candidate]
		evaluator.getKnownList().forEachNpc(peer -> {
			if (peer == evaluator) return;
			if (!peer.getTribe().equals(evaluator.getTribe())) return;
			if (PositionUtil.getDistance(evaluator, peer, false) > PACK_RADIUS) return;
			counts[0]++;
			if (peer.getAggroList().isHating(candidate)) counts[1]++;
		});
		return counts[0] == 0 ? 0f : (float) counts[1] / counts[0];
	}

	/**
	 * Head 4: HP pressure — finish the weakest target.
	 * Full score (1.0) below 30% HP, zero above 60% HP, linear ramp between.
	 */
	private static float headHpPressure(Creature candidate) {
		long maxHp = candidate.getLifeStats().getMaxHp();
		if (maxHp <= 0) return 0f;
		float hpFraction = (float) candidate.getLifeStats().getCurrentHp() / maxHp;
		return hpPressureValue(hpFraction);
	}
}
