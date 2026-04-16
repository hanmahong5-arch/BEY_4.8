package com.aionemu.gameserver.ai.swarm;

import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.ai.swarm.debug.SwarmTelemetry;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.controllers.attack.AggroInfo;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.metrics.WorldPulse;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Integration facade for swarm-intelligence hooks.
 *
 * <p>All public methods guard on {@link CustomConfig#SWARM_ENABLED} and are
 * unconditionally safe to call from game-server code.
 *
 * <h3>Behaviour overview</h3>
 * <ul>
 *   <li>{@link #onNpcEnterCombat}    — combat pheromone deposit on first aggro.</li>
 *   <li>{@link #onNpcDied}           — death pheromone + tribal hate broadcast.</li>
 *   <li>{@link #broadcastDistress}   — one-time HP-threshold distress call.</li>
 *   <li>{@link #shouldSuppressDisengage} — refuse giveup while pheromone is hot.</li>
 *   <li>{@link #shouldSuppressReturn}   — abort home-return while danger persists.</li>
 *   <li>{@link #tryInvestigatePheromone}— idle NPC follows pheromone gradient.</li>
 *   <li>{@link #tryReevaluateTarget}    — combat NPC switches to best-scored target.</li>
 * </ul>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class SwarmBehavior {

	/** Radius within which same-tribe allies receive hate broadcast. */
	private static final int  BROADCAST_RADIUS       = 25;
	/** Hate injected on death broadcast. */
	private static final int  BROADCAST_HATE_DEATH   = 1000;
	/** Hate injected on distress broadcast. */
	private static final int  BROADCAST_HATE_DISTRESS = 500;
	/** HP % threshold for distress call. */
	private static final int  DISTRESS_HP_THRESHOLD  = 50;
	/**
	 * How many cells to scan when searching for a pheromone investigation target.
	 * 5 cells × 8 wu/cell = 40 wu radius.
	 */
	private static final int  INVESTIGATION_RADIUS_CELLS = 5;
	/**
	 * Minimum score advantage needed to switch targets in combat.
	 * Prevents nervous target-switching when scores are close.
	 */
	private static final float TARGET_SWITCH_THRESHOLD = 0.15f;

	private SwarmBehavior() {}

	// -----------------------------------------------------------------------
	// Hook 1 — NPC enters combat (first aggro)
	// -----------------------------------------------------------------------

	/** Deposits combat pheromone when NPC is first aggroed. */
	public static void onNpcEnterCombat(Npc npc, Creature attacker) {
		if (!CustomConfig.SWARM_ENABLED) return;
		SwarmPheromoneGrid.getInstance().deposit(
			npc.getX(), npc.getY(), npc.getWorldId(), SwarmPheromoneGrid.DEPOSIT_COMBAT
		);
		SwarmTelemetry.getInstance().recordDeposit();
		WorldPulse.getInstance().recordPheromoneDeposit(npc.getWorldId());
		// Spawn-trap: emit distress immediately if already low HP
		if (hpPercent(npc) < DISTRESS_HP_THRESHOLD) {
			broadcastDistress(npc, attacker);
		}
	}

	// -----------------------------------------------------------------------
	// Hook 2 — NPC died
	// -----------------------------------------------------------------------

	/** Deposits death pheromone and broadcasts hate to nearby same-tribe allies. */
	public static void onNpcDied(Npc npc, Creature killer) {
		if (!CustomConfig.SWARM_ENABLED) return;
		SwarmPheromoneGrid.getInstance().deposit(
			npc.getX(), npc.getY(), npc.getWorldId(), SwarmPheromoneGrid.DEPOSIT_DEATH
		);
		SwarmTelemetry.getInstance().recordDeposit();
		WorldPulse.getInstance().recordPheromoneDeposit(npc.getWorldId());
		if (killer != null) {
			broadcastHate(npc, killer, BROADCAST_HATE_DEATH, BROADCAST_RADIUS);
		}
	}

	// -----------------------------------------------------------------------
	// Hook 3 — HP-threshold distress call (call from onAttack when HP<50%)
	// -----------------------------------------------------------------------

	/**
	 * Broadcasts a distress call once per fight encounter (self-limiting via
	 * pheromone saturation — fires only when local pheromone < DEPOSIT_DEATH).
	 */
	public static void broadcastDistress(Npc npc, Creature attacker) {
		if (!CustomConfig.SWARM_ENABLED) return;
		int cur = SwarmPheromoneGrid.getInstance().sample(npc.getX(), npc.getY(), npc.getWorldId());
		if (cur >= SwarmPheromoneGrid.DEPOSIT_DEATH) return; // already saturated
		// Saturate so subsequent hits don't re-broadcast
		SwarmPheromoneGrid.getInstance().deposit(
			npc.getX(), npc.getY(), npc.getWorldId(), SwarmPheromoneGrid.DEPOSIT_DEATH
		);
		SwarmTelemetry.getInstance().recordDeposit();
		SwarmTelemetry.getInstance().recordDistressBroadcast();
		broadcastHate(npc, attacker, BROADCAST_HATE_DISTRESS, BROADCAST_RADIUS);
	}

	// -----------------------------------------------------------------------
	// Hook 4 — Disengage suppression (called from AttackManager.checkGiveupDistance)
	// -----------------------------------------------------------------------

	/**
	 * NPCs refuse to disengage while local pheromone is above persistence threshold.
	 *
	 * <p>Checks a 5×5 cell window around the NPC (40 wu radius), not just the
	 * NPC's own cell. This matters during extended chases: an NPC that drifted
	 * 2-3 cells from its initial deposit still sees its own pheromone.
	 */
	public static boolean shouldSuppressDisengage(Npc npc) {
		if (!CustomConfig.SWARM_ENABLED) return false;
		// Radius 5 cells = 40 wu — covers the default 50 wu chase-giveup leash so
		// an NPC that drifted anywhere within its own chase corridor still picks up
		// its deposited pheromone trail. (11×11 = 121 cell lookups, sub-μs.)
		int nearbyMax = SwarmPheromoneGrid.getInstance().maxIntensityNearby(
			npc.getX(), npc.getY(), npc.getWorldId(), 5);
		boolean suppress = nearbyMax >= SwarmPheromoneGrid.THRESHOLD_PERSIST;
		if (suppress) SwarmTelemetry.getInstance().recordDisengageSuppressed();
		return suppress;
	}

	/**
	 * Lightly refresh pheromone at the NPC's current position during ongoing combat.
	 *
	 * <p>Called from {@code NpcController.onAttack} when the NPC takes damage.
	 * This creates a "combat trail" of the NPC's chase path — fights that last
	 * longer than the 60s half-life stay persistent by continuous refresh.
	 *
	 * <p>Self-limiting: no-op when local pheromone already ≥ 1000 (avoid waste).
	 */
	public static void refreshCombatPheromone(Npc npc) {
		if (!CustomConfig.SWARM_ENABLED) return;
		int cur = SwarmPheromoneGrid.getInstance().sample(npc.getX(), npc.getY(), npc.getWorldId());
		if (cur >= 1000) return;  // cell saturated — skip write
		SwarmPheromoneGrid.getInstance().deposit(
			npc.getX(), npc.getY(), npc.getWorldId(), 150);
		SwarmTelemetry.getInstance().recordDeposit();
	}

	// -----------------------------------------------------------------------
	// Hook 5 — Return-home suppression (called from ReturningEventHandler)
	// -----------------------------------------------------------------------

	/**
	 * True when the NPC is standing in a still-hot pheromone zone — it should
	 * NOT continue walking home, but stay alert and let normal aggro re-engage it.
	 */
	public static boolean shouldSuppressReturn(Npc npc) {
		if (!CustomConfig.SWARM_ENABLED) return false;
		boolean suppress = SwarmPheromoneGrid.getInstance().isReturnSuppressed(npc.getX(), npc.getY(), npc.getWorldId());
		if (suppress) SwarmTelemetry.getInstance().recordReturnSuppressed();
		return suppress;
	}

	// -----------------------------------------------------------------------
	// Hook 6 — Idle investigation (called from ThinkEventHandler.thinkIdle)
	// -----------------------------------------------------------------------

	/**
	 * Moves an idle NPC toward the nearest pheromone hot-zone within sensing range.
	 * When the NPC arrives, the normal known-list aggro scan will naturally pick up
	 * any enemies present — no manual hate injection needed.
	 *
	 * @return true if the NPC was directed toward a pheromone source
	 */
	public static boolean tryInvestigatePheromone(NpcAI npcAI) {
		if (!CustomConfig.SWARM_ENABLED) return false;
		if (!CustomConfig.SWARM_INVESTIGATE_ENABLED) return false;  // kill switch
		Npc npc = npcAI.getOwner();
		if (!npcAI.isMoveSupported()) return false;

		// CRITICAL: Tail-chasing prevention.
		// If the NPC is already standing in a hot cell, it's IN its own residual
		// combat zone — don't wander toward another cell of the same zone, which
		// would cause ping-pong oscillation between adjacent deposit cells.
		// Wait for the zone to decay before seeking fresh combat elsewhere.
		SwarmPheromoneGrid grid = SwarmPheromoneGrid.getInstance();
		int localPheromone = grid.sample(npc.getX(), npc.getY(), npc.getWorldId());
		if (localPheromone >= 100) {
			SwarmTelemetry.getInstance().recordInvestigation(false);
			return false;
		}

		float[] hotspot = grid.findStrongestNearby(
			npc.getX(), npc.getY(), npc.getWorldId(), INVESTIGATION_RADIUS_CELLS
		);
		if (hotspot == null) {
			SwarmTelemetry.getInstance().recordInvestigation(false);
			return false;
		}

		// Minimum investigation distance increased: 32 wu (4 cells) to ensure we're
		// moving to a genuinely different location, not one cell over.
		float dist = (float) Math.sqrt(
			Math.pow(hotspot[0] - npc.getX(), 2) + Math.pow(hotspot[1] - npc.getY(), 2)
		);
		if (dist < SwarmPheromoneGrid.CELL_SIZE * 4) {
			SwarmTelemetry.getInstance().recordInvestigation(false);
			return false;
		}

		// Geo validation — must NOT move through walls or onto invalid terrain.
		float destZ = GeoService.getInstance().getZ(npc.getWorldId(), hotspot[0], hotspot[1],
			npc.getZ() + 5, npc.getZ() - 5, npc.getInstanceId());
		if (Float.isNaN(destZ)) {
			SwarmTelemetry.getInstance().recordInvestigation(false);
			return false;
		}
		if (!GeoService.getInstance().canSee(npc, hotspot[0], hotspot[1], destZ, IgnoreProperties.ANY_RACE)) {
			SwarmTelemetry.getInstance().recordInvestigation(false);
			return false;
		}

		npc.getMoveController().moveToPoint(hotspot[0], hotspot[1], destZ);
		SwarmTelemetry.getInstance().recordInvestigation(true);
		return true;
	}

	// -----------------------------------------------------------------------
	// Hook 7 — Target re-evaluation during combat (called from ThinkEventHandler.thinkAttack)
	// -----------------------------------------------------------------------

	/**
	 * Re-scores all aggro targets and switches to the highest-priority one if its
	 * score advantage exceeds {@value #TARGET_SWITCH_THRESHOLD}.
	 *
	 * <p>This is what makes NPCs "think" collectively: the HP-pressure head causes
	 * them to pile on low-HP targets (emergent focus fire), the consensus head
	 * makes pack-mates reinforce each other's targeting choices.
	 *
	 * @return true if the NPC switched to a new target
	 */
	public static boolean tryReevaluateTarget(NpcAI npcAI) {
		if (!CustomConfig.SWARM_ENABLED) return false;
		long t0 = System.nanoTime();
		try {
			Npc npc = npcAI.getOwner();

			// Need at least 2 candidates to bother scoring
			long candidateCount = npc.getAggroList().stream()
				.filter(e -> e.getHate() > 0 && !e.getAttacker().isDead())
				.count();
			if (candidateCount < 2) {
				SwarmTelemetry.getInstance().recordReevaluate(false);
				return false;
			}

			Creature current = npc.getTarget() instanceof Creature c ? c : null;
			Creature best = NpcAttentionScorer.bestTarget(npc);
			if (best == null || best.equals(current)) {
				SwarmTelemetry.getInstance().recordReevaluate(false);
				return false;
			}

			// Only switch if the score advantage is significant
			SwarmPheromoneGrid grid = SwarmPheromoneGrid.getInstance();
			float currentScore = current == null ? 0f : NpcAttentionScorer.score(
				npc, current,
				npc.getAggroList().getHate(current),
				grid.sample(current.getX(), current.getY(), current.getWorldId())
			);
			float bestScore = NpcAttentionScorer.score(
				npc, best,
				npc.getAggroList().getHate(best),
				grid.sample(best.getX(), best.getY(), best.getWorldId())
			);
			if (bestScore - currentScore < TARGET_SWITCH_THRESHOLD) {
				SwarmTelemetry.getInstance().recordReevaluate(false);
				return false;
			}

			// Record the decision before firing — so the decision log reflects the switch
			SwarmTelemetry.getInstance().recordDecision(
				npc.getObjectId(),
				npc.getName(),
				current == null ? null : current.getName(),
				best.getName(),
				currentScore,
				bestScore
			);
			SwarmTelemetry.getInstance().recordReevaluate(true);

			// Fire the standard target-changed event so all downstream handlers run
			npcAI.onCreatureEvent(AIEventType.TARGET_CHANGED, best);
			return true;
		} finally {
			SwarmTelemetry.getInstance().recordThinkAttackTime(System.nanoTime() - t0);
		}
	}

	// -----------------------------------------------------------------------
	// Internal helpers
	// -----------------------------------------------------------------------

	/** Inject hate for target into same-tribe NPCs within radius of origin. */
	private static void broadcastHate(Npc origin, Creature target, int hate, int radius) {
		origin.getKnownList().forEachNpc(peer -> {
			if (peer == origin || peer.isDead()) return;
			if (!peer.getTribe().equals(origin.getTribe())) return;
			if (PositionUtil.getDistance(origin, peer, false) > radius) return;
			peer.getAggroList().addHate(target, hate);
		});
	}

	private static int hpPercent(Npc npc) {
		long max = npc.getLifeStats().getMaxHp();
		return max <= 0 ? 100 : (int) ((long) npc.getLifeStats().getCurrentHp() * 100 / max);
	}
}
