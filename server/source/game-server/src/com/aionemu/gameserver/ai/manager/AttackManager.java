package com.aionemu.gameserver.ai.manager;

import com.aionemu.gameserver.ai.AILogger;
import com.aionemu.gameserver.ai.AISubState;
import com.aionemu.gameserver.ai.AttackIntention;
import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.controllers.attack.AggroTarget;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Ported back to Aion-Lightning upstream semantics 2026-04-14.
 * Removed Z-axis substate canSee extension, pheromone disengage suppression,
 * and all custom chase retry logic — they were interacting with the new
 * ReturningEventHandler / NpcMoveController changes to leave NPCs stuck.
 *
 * @author ATracer
 */
public class AttackManager {

	public static void startAttacking(NpcAI npcAI) {
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "AttackManager: startAttacking");
		}
		npcAI.getOwner().getGameStats().setFightStartingTime();
		VisibleObject target = npcAI.getOwner().getTarget();
		if (target instanceof Creature) // may be null at this point (-> triggering then TARGET_GIVEUP)
			EmoteManager.emoteStartAttacking(npcAI.getOwner(), (Creature) target);
		scheduleNextAttack(npcAI);
	}

	public static void scheduleNextAttack(NpcAI npcAI) {
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "AttackManager: scheduleNextAttack");
		}
		// don't start attack while in casting substate
		AISubState subState = npcAI.getSubState();
		if (subState == AISubState.NONE) {
			chooseAttack(npcAI, npcAI.getOwner().getGameStats().getNextAttackInterval());
		} else {
			if (npcAI.isLogging()) {
				AILogger.info(npcAI, "Will not choose attack in substate" + subState);
			}
		}
	}

	protected static void chooseAttack(NpcAI npcAI, int delay) {
		AttackIntention attackIntention = npcAI.chooseAttackIntention();
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "AttackManager: chooseAttack " + attackIntention + " delay " + delay);
		}
		if (!npcAI.canThink()) {
			return;
		}
		switch (attackIntention) {
			case SIMPLE_ATTACK:
				SimpleAttackManager.performAttack(npcAI, delay);
				break;
			case SKILL_ATTACK:
				SkillAttackManager.performAttack(npcAI, delay);
				break;
			case FINISH_ATTACK:
				npcAI.think();
				break;
		}
	}

	public static void targetTooFar(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "AttackManager: attackTimeDelta " + npc.getGameStats().getLastAttackTimeDelta());
		}

		// switch target if there is more hated creature
		if (npc.getGameStats().getLastChangeTargetTimeDelta() > 5) {
			Creature mostHated = npc.getAggroList().getTarget(AggroTarget.MOST_HATED);
			if (mostHated != null && !npc.isTargeting(mostHated.getObjectId())) {
				if (npcAI.isLogging()) {
					AILogger.info(npcAI, "AttackManager: switching target during chase");
				}
				npcAI.onCreatureEvent(AIEventType.TARGET_CHANGED, mostHated);
				return;
			}
		}
		// Upstream AL-Aion: lost LoS → give up. Keeps chase bounded and prevents
		// NPCs from pathing into impossible geometry chasing a target they can't see.
		VisibleObject target = npc.getTarget();
		if (!(target instanceof Creature) || !npc.canSee((Creature) target)) {
			npcAI.onGeneralEvent(AIEventType.TARGET_GIVEUP);
			return;
		}
		if (checkGiveupDistance(npcAI)) {
			npcAI.onGeneralEvent(AIEventType.TARGET_GIVEUP);
			return;
		}
		if (npcAI.isMoveSupported()) {
			npc.getMoveController().moveToTargetObject();
			return;
		}
		npcAI.onGeneralEvent(AIEventType.TARGET_GIVEUP);
	}

	private static boolean checkGiveupDistance(NpcAI npcAI) {
		Npc npc = npcAI.getOwner();
		VisibleObject target = npc.getTarget();
		if (target != null) {
			if (npcAI.isLogging())
				AILogger.info(npcAI, "AttackManager: distanceToTarget " + PositionUtil.getDistance(npc, target, false));
			int maxChaseDistance = npc.isBoss() ? 50 : npc.getPosition().getWorldMapInstance().getTemplate().getAiInfo().getChaseTarget();
			if (!PositionUtil.isInRange(npc, target, maxChaseDistance))
				return true;
		}
		double distanceToHome = npc.getDistanceToSpawnLocation();
		int chaseHome = npc.isBoss() ? 150 : npc.getPosition().getWorldMapInstance().getTemplate().getAiInfo().getChaseHome();
		if (distanceToHome > chaseHome) {
			return true;
		}
		return false;
	}
}
