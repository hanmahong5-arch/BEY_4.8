package com.aionemu.gameserver.ai.handler;

import java.util.List;

import com.aionemu.gameserver.ai.AILogger;
import com.aionemu.gameserver.ai.AIState;
import com.aionemu.gameserver.ai.AISubState;
import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.event.AIEventType;
import com.aionemu.gameserver.ai.manager.EmoteManager;
import com.aionemu.gameserver.ai.manager.WalkManager;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.skill.NpcSkillEntry;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.model.DispelSlotType;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * Ported back to Aion-Lightning upstream semantics 2026-04-14.
 *
 * Removed: DISPLACED linger + scheduleDisplacedWander + swarm suppression.
 * The linger branch was guarded by setStateIfNot(IDLE), but TargetEventHandler
 * had already set state to IDLE — so the branch silently no-op'd and NPCs froze
 * at their last combat position, unable to return home or ever chase again.
 *
 * New behavior: straight IDLE → RETURNING transition, walk back via stored
 * backstep or spawn. HP regen tick retained (BEY custom feature).
 *
 * @author ATracer
 */
public class ReturningEventHandler {

	/**
	 * Called when NPC loses aggro and is not at its spawn point.
	 * Enters RETURNING state and walks back to spawn.
	 */
	public static void onNotAtHome(NpcAI npcAI) {
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "onNotAtHome");
		}
		if (!npcAI.isMoveSupported()) {
			npcAI.onGeneralEvent(AIEventType.BACK_HOME);
			return;
		}
		// TargetEventHandler.onTargetReached has already flipped state to IDLE
		// before firing NOT_AT_HOME, so the transition here is IDLE → RETURNING.
		if (npcAI.setStateIfNot(AIState.RETURNING)) {
			npcAI.setSubStateIfNot(AISubState.NONE);
			Npc npc = npcAI.getOwner();
			EmoteManager.emoteStartReturning(npc);
			startReturnRegen(npc);
			if (npc.isPathWalker() && WalkManager.startWalking(npcAI))
				return;
			npc.getMoveController().returnToLastStepOrSpawn();
		}
	}

	/**
	 * Gradual HP regen during return walk.
	 * Heals 5% HP every second for 20 seconds (total 100%).
	 */
	private static void startReturnRegen(Npc npc) {
		for (int i = 1; i <= 20; i++) {
			ThreadPoolManager.getInstance().schedule(() -> {
				if (npc.isSpawned() && !npc.isDead()) {
					int currentPct = (int) ((long) npc.getLifeStats().getCurrentHp() * 100
						/ npc.getLifeStats().getMaxHp());
					if (currentPct < 100) {
						npc.getLifeStats().setCurrentHpPercent(Math.min(100, currentPct + 5));
					}
				}
			}, i * 1000L);
		}
	}

	/**
	 * @param npcAI
	 */
	public static void onBackHome(NpcAI npcAI) {
		if (npcAI.isLogging()) {
			AILogger.info(npcAI, "onBackHome");
		}
		npcAI.getOwner().getMoveController().clearBackSteps();
		if (npcAI.setStateIfNot(AIState.IDLE)) {
			npcAI.setSubStateIfNot(AISubState.NONE);
			npcAI.getOwner().getEffectController().removeByDispelSlotType(DispelSlotType.BUFF);
			EmoteManager.emoteStartIdling(npcAI.getOwner());
			// Enter alert state: expanded aggro range for a configurable duration
			Npc npc = npcAI.getOwner();
			npc.setAlerted(CustomConfig.NPC_ALERT_DURATION_MS);
			// Reset weakness accumulator and break state so the next combat session starts clean
			npc.weaknessAccum = 0;
			npc.weaknessBreakUntilMs = 0;
			npcAI.think();
			List<NpcSkillEntry> skills = npc.getSkillList().getPostSpawnSkills();
			if (!skills.isEmpty())
				skills.forEach(s -> SkillEngine.getInstance().getSkill(npc, s.getSkillId(), s.getSkillLevel(), npc).useWithoutPropSkill());
		}
		npcAI.getOwner().getPosition().getWorldMapInstance().getInstanceHandler().onBackHome(npcAI.getOwner());
	}
}
