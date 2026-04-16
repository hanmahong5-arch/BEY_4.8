package com.aionemu.gameserver.ai;

/**
 * @author ATracer
 */
public enum AISubState {
	NONE,
	TALK,
	CAST,
	WALK_PATH,
	WALK_RANDOM,
	WALK_WAIT_GROUP,
	FREEZE,
	TARGET_LOST,
	DISPLACED  // NPC lingers at chase endpoint after losing aggro (living world)
}
