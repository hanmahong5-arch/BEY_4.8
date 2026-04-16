package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

public class GeoDataConfig {

	/**
	 * Geodata enable
	 */
	@Property(key = "gameserver.geodata.enable", defaultValue = "true")
	public static boolean GEO_ENABLE;

	/**
	 * Enable canSee checks using geodata.
	 */
	@Property(key = "gameserver.geodata.cansee.enable", defaultValue = "true")
	public static boolean CANSEE_ENABLE;

	/**
	 * Enable Fear skill using geodata.
	 */
	@Property(key = "gameserver.geodata.fear.enable", defaultValue = "true")
	public static boolean FEAR_ENABLE;

	/**
	 * Enable Geo checks during npc movement (prevent flying mobs)
	 */
	@Property(key = "gameserver.geodata.npc.move", defaultValue = "true")
	public static boolean GEO_NPC_MOVE;

	/**
	 * Enable geo materials using skills
	 */
	@Property(key = "gameserver.geodata.materials.enable", defaultValue = "true")
	public static boolean GEO_MATERIALS_ENABLE;

	/**
	 * Show collision zone name and skill id
	 */
	@Property(key = "gameserver.geodata.materials.showdetails", defaultValue = "false")
	public static boolean GEO_MATERIALS_SHOWDETAILS;

	/**
	 * Enable geo shields
	 */
	@Property(key = "gameserver.geodata.shields.enable", defaultValue = "true")
	public static boolean GEO_SHIELDS_ENABLE;

	/**
	 * Enable A* pathfinding for NPC chase/follow movement.
	 * When enabled, NPCs compute grid-based paths around obstacles
	 * instead of using simple angle-probing.
	 */
	@Property(key = "gameserver.geodata.pathfinding.enable", defaultValue = "true")
	public static boolean GEO_PATHFINDING;

	/**
	 * Maximum A* nodes to expand per path computation.
	 * Higher = longer paths but more CPU. 200 nodes = ~400m detour coverage.
	 */
	@Property(key = "gameserver.geodata.pathfinding.max_nodes", defaultValue = "200")
	public static int PATHFINDING_MAX_NODES;

	/**
	 * Minimum interval (ms) between path recomputation checks.
	 */
	@Property(key = "gameserver.geodata.pathfinding.recheck_interval", defaultValue = "100")
	public static int PATHFINDING_RECHECK_MS;

	/**
	 * After this many ms of persistent A* failure (no path found even at max_nodes),
	 * the NPC gives up pursuit and returns home.
	 * Set to 0 to disable give-up (NPC will chase indefinitely).
	 */
	@Property(key = "gameserver.geodata.pathfinding.giveup_ms", defaultValue = "30000")
	public static int PATHFINDING_GIVEUP_MS;

}
