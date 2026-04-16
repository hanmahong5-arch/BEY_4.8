package com.aionemu.gameserver.ai.utility;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.utility.goals.DefendTerritoryGoal;
import com.aionemu.gameserver.ai.utility.goals.PatrolGoal;
import com.aionemu.gameserver.ai.utility.goals.RestGoal;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.Npc;

/**
 * Singleton dispatcher for {@link UtilityGoal} evaluation.
 *
 * <p>Maintains a whitelist of NPC template IDs that are eligible for utility
 * AI behaviour — the rest of the world is untouched. The whitelist is loaded
 * from {@link CustomConfig#UTILITY_AI_NPC_IDS} on first access and can be
 * mutated at runtime via the {@code //utility} admin command for testing.
 *
 * <p>Evaluation is triggered from {@code ThinkEventHandler.thinkIdle()}: when
 * a whitelisted NPC enters IDLE state, all goals are scored, the highest is
 * picked, and {@code execute()} is invoked. If the goal starts a movement,
 * the next think cycle happens after movement completes — natural pacing
 * with no explicit cooldowns required.
 *
 * <p><b>Performance contract</b>: when an NPC is NOT in the whitelist,
 * {@link #tryExecute(NpcAI)} performs exactly one {@code HashSet.contains}
 * call and returns false. This is the cost on the IDLE think path for the
 * 99.9% of NPCs not running utility AI.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class UtilityController {

	private static final Logger log = LoggerFactory.getLogger(UtilityController.class);

	/** Goals are evaluated in this fixed order — ties broken by first-wins. */
	private static final List<UtilityGoal> GOALS = List.of(
		new PatrolGoal(),
		new DefendTerritoryGoal(),
		new RestGoal()
	);

	private static final UtilityController INSTANCE = new UtilityController();
	public static UtilityController getInstance() { return INSTANCE; }

	/** Whitelist: which NPC template IDs run utility AI. */
	private final Set<Integer> whitelist = ConcurrentHashMap.newKeySet();

	/** Lightweight per-NPC stats for //utility info. */
	private final ConcurrentHashMap<Integer, LastChoice> lastChoiceByNpc = new ConcurrentHashMap<>();

	private volatile boolean loaded = false;

	private UtilityController() {}

	private synchronized void loadWhitelistIfNeeded() {
		if (loaded) return;
		String csv = CustomConfig.UTILITY_AI_NPC_IDS;
		if (csv != null && !csv.isBlank()) {
			Arrays.stream(csv.split("[,;\\s]+"))
				.filter(s -> !s.isBlank())
				.forEach(s -> {
					try { whitelist.add(Integer.parseInt(s.trim())); }
					catch (NumberFormatException nfe) { log.warn("UtilityAI: bad npc id in config: {}", s); }
				});
		}
		loaded = true;
		log.info("UtilityController loaded {} whitelisted NPC ids", whitelist.size());
	}

	// -----------------------------------------------------------------------
	// Public API — called from ThinkEventHandler.thinkIdle
	// -----------------------------------------------------------------------

	/**
	 * Try to run a utility goal for this NPC.
	 *
	 * @return true if a goal was executed (caller should not run other idle behaviour);
	 *         false if the NPC is not whitelisted, no goal scored, or chosen goal did nothing
	 */
	public boolean tryExecute(NpcAI npcAI) {
		if (!CustomConfig.UTILITY_AI_ENABLED) return false;
		if (!loaded) loadWhitelistIfNeeded();
		Npc npc = npcAI.getOwner();
		if (!whitelist.contains(npc.getNpcId())) return false;

		// Score all goals
		UtilityGoal best = null;
		float bestScore = 0f;
		for (UtilityGoal g : GOALS) {
			float s = g.score(npcAI);
			if (s > bestScore) { bestScore = s; best = g; }
		}
		if (best == null) return false;

		boolean executed = best.execute(npcAI);
		if (executed) {
			lastChoiceByNpc.put(npc.getObjectId(), new LastChoice(best.name(), bestScore, System.currentTimeMillis()));
		}
		return executed;
	}

	// -----------------------------------------------------------------------
	// Whitelist management — used by //utility admin command
	// -----------------------------------------------------------------------

	public boolean addWhitelist(int npcId) {
		loadWhitelistIfNeeded();
		return whitelist.add(npcId);
	}

	public boolean removeWhitelist(int npcId) {
		loadWhitelistIfNeeded();
		return whitelist.remove(npcId);
	}

	public Set<Integer> whitelist() {
		loadWhitelistIfNeeded();
		return Collections.unmodifiableSet(new HashSet<>(whitelist));
	}

	public LastChoice lastChoiceFor(int npcObjectId) {
		return lastChoiceByNpc.get(npcObjectId);
	}

	public List<UtilityGoal> goals() {
		return GOALS;
	}

	public void disableAll() {
		whitelist.clear();
		log.warn("UtilityController: all whitelisted NPCs disabled by admin");
	}

	// -----------------------------------------------------------------------
	// Diagnostics record
	// -----------------------------------------------------------------------

	public record LastChoice(String goalName, float score, long whenMs) {}
}
