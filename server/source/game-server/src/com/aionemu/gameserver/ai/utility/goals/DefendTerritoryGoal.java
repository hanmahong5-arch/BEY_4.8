package com.aionemu.gameserver.ai.utility.goals;

import java.util.List;

import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.swarm.SwarmPheromoneGrid;
import com.aionemu.gameserver.ai.utility.UtilityGoal;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.metrics.WorldPulse;
import com.aionemu.gameserver.metrics.WorldPulse.RegionEntry;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * Defend Territory — when the NPC's home map is hot (RegionHeat above
 * threshold), move toward the closest pheromone hot-spot. Idiomatic
 * "swarm rallying" behaviour: NPCs from the wider area converge on
 * trouble spots without explicit coordination.
 *
 * <p>Score formula: scaled with current-interval RegionHeat for the NPC's
 * map. Scores 0.0 below threshold, ramps up to 0.85 at very high activity.
 *
 * <p>Action: find the strongest nearby pheromone cell (radius 8 cells = 64
 * wu) and move toward it. The NPC's normal aggro detection picks up enemies
 * upon arrival.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class DefendTerritoryGoal implements UtilityGoal {

	private static final int   DEFEND_THRESHOLD   = 5;   // deposits/min minimum to care
	private static final int   DEFEND_HEAT_FULL   = 30;  // deposits/min to fully saturate
	private static final float SCORE_FLOOR        = 0.55f;
	private static final float SCORE_CEILING      = 0.85f;
	private static final int   GRADIENT_RADIUS    = 8;   // cells (64 wu)

	@Override
	public String name() { return "defend"; }

	@Override
	public float score(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		// Only same-map heat counts
		List<RegionEntry> top = WorldPulse.getInstance().topRegions(20);
		int heat = top.stream()
			.filter(e -> e.mapId() == npc.getWorldId())
			.mapToInt(RegionEntry::heat)
			.findFirst()
			.orElse(0);
		if (heat < DEFEND_THRESHOLD) return 0f;
		// Linear ramp to ceiling
		float frac = Math.min(1f, (float)(heat - DEFEND_THRESHOLD) / (DEFEND_HEAT_FULL - DEFEND_THRESHOLD));
		return SCORE_FLOOR + (SCORE_CEILING - SCORE_FLOOR) * frac;
	}

	@Override
	public boolean execute(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		SwarmPheromoneGrid grid = SwarmPheromoneGrid.getInstance();

		// Tail-chase prevention — if NPC is already in a hot zone, stay put.
		if (grid.sample(npc.getX(), npc.getY(), npc.getWorldId()) >= 100) {
			return false;
		}

		float[] hotspot = grid.findStrongestNearby(
			npc.getX(), npc.getY(), npc.getWorldId(), GRADIENT_RADIUS);
		if (hotspot == null) return false;

		// Minimum travel distance — don't move for one-cell hotspots
		float dist = (float) Math.sqrt(
			Math.pow(hotspot[0] - npc.getX(), 2) + Math.pow(hotspot[1] - npc.getY(), 2));
		if (dist < SwarmPheromoneGrid.CELL_SIZE * 4) return false;

		// Geo validation — no wall phasing.
		float z = GeoService.getInstance().getZ(npc.getWorldId(), hotspot[0], hotspot[1],
			npc.getZ() + 5, npc.getZ() - 5, npc.getInstanceId());
		if (Float.isNaN(z)) return false;
		if (!GeoService.getInstance().canSee(npc, hotspot[0], hotspot[1], z, IgnoreProperties.ANY_RACE)) {
			return false;
		}
		npc.getMoveController().moveToPoint(hotspot[0], hotspot[1], z);
		return true;
	}
}
