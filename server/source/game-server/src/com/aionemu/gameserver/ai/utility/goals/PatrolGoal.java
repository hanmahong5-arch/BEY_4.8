package com.aionemu.gameserver.ai.utility.goals;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.utility.UtilityGoal;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Patrol — wander to a random point near the NPC's spawn location.
 *
 * <p>Score: 0.5 baseline if the NPC is at or near its spawn (so it has
 * "nothing else to do"). The intent is to be a low-priority fallback that
 * loses out to combat-driven goals like Defend.
 *
 * <p>Behaviour: pick a random point within {@value #PATROL_RADIUS} world
 * units of the spawn point. Skip if geometry rejects the Z coordinate
 * (off the navmesh).
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class PatrolGoal implements UtilityGoal {

	private static final float PATROL_RADIUS = 18f;
	private static final float HOME_RADIUS   = 12f;

	@Override
	public String name() { return "patrol"; }

	@Override
	public float score(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		// Only patrol when at or near home
		if (npc.getDistanceToSpawnLocation() > HOME_RADIUS) return 0f;
		// Patrol baseline — low priority so combat-driven goals win
		return 0.50f;
	}

	@Override
	public boolean execute(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		float sx = npc.getSpawn().getX();
		float sy = npc.getSpawn().getY();
		float sz = npc.getSpawn().getZ();

		// Pick a random offset within PATROL_RADIUS
		float angle = Rnd.nextFloat() * (float) (Math.PI * 2);
		float dist  = HOME_RADIUS + Rnd.nextFloat() * (PATROL_RADIUS - HOME_RADIUS);
		float nx = sx + (float) Math.cos(angle) * dist;
		float ny = sy + (float) Math.sin(angle) * dist;
		float nz = GeoService.getInstance().getZ(npc.getWorldId(), nx, ny,
			sz + 3, sz - 3, npc.getInstanceId());
		if (Float.isNaN(nz)) return false;
		// Line-of-sight check — never patrol through walls
		if (!GeoService.getInstance().canSee(npc, nx, ny, nz, IgnoreProperties.ANY_RACE)) return false;

		npc.getMoveController().moveToPoint(nx, ny, nz);
		return true;
	}
}
