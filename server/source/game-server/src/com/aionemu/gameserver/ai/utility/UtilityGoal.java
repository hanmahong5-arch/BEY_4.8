package com.aionemu.gameserver.ai.utility;

import com.aionemu.gameserver.ai.NpcAI;

/**
 * A long-term behavioural goal for an NPC, evaluated periodically by the
 * {@link UtilityController} when the NPC is idle.
 *
 * <p>Inspired by utility-based AI from <em>The Sims</em> / <em>F.E.A.R.</em>:
 * each goal independently decides how strongly it wants to run right now
 * (its score), and the controller picks the one with the highest score.
 *
 * <p>Goals are stateless singletons. All per-NPC state lives in the NPC
 * itself or is recomputed each evaluation. This keeps the system trivially
 * concurrent and avoids reference leaks when NPCs despawn.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #score} must be pure (no side effects) and return [0, 1].</li>
 *   <li>{@link #execute} runs the chosen action — typically a move command.
 *       It returns true if it actually started something, false if the goal
 *       chose to do nothing this tick.</li>
 *   <li>{@link #name} is for diagnostics ({@code //utility info}).</li>
 * </ul>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public interface UtilityGoal {

	/**
	 * @return a name suitable for the //utility admin command output
	 */
	String name();

	/**
	 * Score how much this NPC wants to pursue this goal right now.
	 *
	 * @param npcAI the idle NPC that is choosing a goal
	 * @return [0, 1] — higher means stronger desire to run; 0 means do not run
	 */
	float score(NpcAI npcAI);

	/**
	 * Execute the goal's action for this NPC. Called by the controller after
	 * this goal is selected. Should be cheap — typically issues a single move
	 * command and returns.
	 *
	 * @param npcAI the NPC executing the goal
	 * @return true if an action was started, false if the goal had nothing to do
	 */
	boolean execute(NpcAI npcAI);
}
