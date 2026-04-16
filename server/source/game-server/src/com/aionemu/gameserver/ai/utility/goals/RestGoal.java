package com.aionemu.gameserver.ai.utility.goals;

import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.utility.UtilityGoal;
import com.aionemu.gameserver.model.gameobjects.Npc;

/**
 * Rest — when wounded and far from spawn, walk back home to recover.
 *
 * <p>Score formula: scales with HP loss × distance from spawn. Maxes out
 * around 0.75 (high but loses to active combat goals).
 *
 * <p>Action: walk straight back to spawn point. The existing return-home
 * regen mechanism (from ReturningEventHandler) takes over once the NPC
 * arrives.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class RestGoal implements UtilityGoal {

	private static final float REST_DISTANCE_THRESHOLD = 8f;
	private static final float MAX_SCORE = 0.75f;

	@Override
	public String name() { return "rest"; }

	@Override
	public float score(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		long maxHp = npc.getLifeStats().getMaxHp();
		if (maxHp <= 0) return 0f;
		float hpFrac = (float) npc.getLifeStats().getCurrentHp() / maxHp;
		if (hpFrac >= 0.99f) return 0f;  // not wounded → no rest urgency

		double distHome = npc.getDistanceToSpawnLocation();
		if (distHome < REST_DISTANCE_THRESHOLD) return 0f;  // already at home

		// Two factors: (1 - hpFrac) and distHome / 50
		float hpUrgency   = 1f - hpFrac;
		float distUrgency = (float) Math.min(1.0, distHome / 50.0);
		return MAX_SCORE * hpUrgency * distUrgency;
	}

	@Override
	public boolean execute(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		float sx = npc.getSpawn().getX();
		float sy = npc.getSpawn().getY();
		float sz = npc.getSpawn().getZ();
		npc.getMoveController().moveToPoint(sx, sy, sz);
		return true;
	}
}
