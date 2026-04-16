package admincommands;

import java.util.Set;

import com.aionemu.gameserver.ai.utility.UtilityController;
import com.aionemu.gameserver.ai.utility.UtilityController.LastChoice;
import com.aionemu.gameserver.ai.utility.UtilityGoal;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * Admin command for the Utility AI subsystem.
 *
 * <p>Subcommands:
 * <pre>
 *   //utility list                    — current whitelist
 *   //utility add <npcId>             — add an NPC template ID
 *   //utility remove <npcId>          — remove an NPC template ID
 *   //utility add target              — add the currently-targeted NPC's templateId
 *   //utility info                    — last-chosen goal for current target
 *   //utility goals                   — list all loaded goals
 *   //utility toggle                  — flip UTILITY_AI_ENABLED (session-only)
 *   //utility disable_all             — clear the entire whitelist (kill switch)
 * </pre>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public class Utility extends AdminCommand {

	public Utility() {
		super("utility", "Utility AI — opt-in long-term-goal layer for marked NPCs.");
		setSyntaxInfo(
			"<list>           - show whitelist",
			"<add> <npcId|target> - add NPC to whitelist",
			"<remove> <npcId> - remove NPC from whitelist",
			"<info>           - show your target's last-chosen goal",
			"<goals>          - list registered goals",
			"<toggle>         - toggle UTILITY_AI_ENABLED",
			"<disable_all>    - clear whitelist (kill switch)"
		);
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			showStatus(admin);
			return;
		}
		switch (params[0].toLowerCase()) {
			case "list"        -> showList(admin);
			case "add"         -> addEntry(admin, params);
			case "remove"      -> removeEntry(admin, params);
			case "info"        -> showInfo(admin);
			case "goals"       -> showGoals(admin);
			case "toggle"      -> toggleEnabled(admin);
			case "disable_all" -> disableAll(admin);
			default            -> sendInfo(admin);
		}
	}

	private void showStatus(Player admin) {
		StringBuilder sb = new StringBuilder("[Utility AI]\n");
		sb.append("  enabled : ").append(CustomConfig.UTILITY_AI_ENABLED).append('\n');
		sb.append("  whitelisted ids : ").append(UtilityController.getInstance().whitelist().size()).append('\n');
		sb.append("  goals registered: ").append(UtilityController.getInstance().goals().size());
		sendInfo(admin, sb.toString());
	}

	private void showList(Player admin) {
		Set<Integer> set = UtilityController.getInstance().whitelist();
		if (set.isEmpty()) {
			sendInfo(admin, "[Utility AI] whitelist empty");
			return;
		}
		sendInfo(admin, "[Utility AI] whitelist: " + set);
	}

	private void addEntry(Player admin, String... params) {
		if (params.length < 2) {
			sendInfo(admin, "Usage: //utility add <npcId|target>");
			return;
		}
		int npcId;
		if ("target".equalsIgnoreCase(params[1])) {
			VisibleObject t = admin.getTarget();
			if (!(t instanceof Npc npc)) {
				sendInfo(admin, "Select an NPC target first.");
				return;
			}
			npcId = npc.getNpcId();
		} else {
			try { npcId = Integer.parseInt(params[1]); }
			catch (NumberFormatException e) { sendInfo(admin, "Bad npcId."); return; }
		}
		boolean added = UtilityController.getInstance().addWhitelist(npcId);
		sendInfo(admin, "[Utility AI] " + (added ? "added " : "already had ") + npcId);
	}

	private void removeEntry(Player admin, String... params) {
		if (params.length < 2) { sendInfo(admin, "Usage: //utility remove <npcId>"); return; }
		try {
			int id = Integer.parseInt(params[1]);
			boolean removed = UtilityController.getInstance().removeWhitelist(id);
			sendInfo(admin, "[Utility AI] " + (removed ? "removed " : "not in whitelist: ") + id);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Bad npcId.");
		}
	}

	private void showInfo(Player admin) {
		VisibleObject t = admin.getTarget();
		if (!(t instanceof Npc npc)) {
			sendInfo(admin, "Select an NPC target first.");
			return;
		}
		LastChoice lc = UtilityController.getInstance().lastChoiceFor(npc.getObjectId());
		if (lc == null) {
			sendInfo(admin, "[Utility AI] " + npc.getName() + " — no choice recorded yet");
			return;
		}
		long ageSec = (System.currentTimeMillis() - lc.whenMs()) / 1000;
		sendInfo(admin, String.format("[Utility AI] %s — last chose '%s' (score=%.3f) %ds ago",
			npc.getName(), lc.goalName(), lc.score(), ageSec));
	}

	private void showGoals(Player admin) {
		StringBuilder sb = new StringBuilder("[Utility AI] registered goals:\n");
		for (UtilityGoal g : UtilityController.getInstance().goals()) {
			sb.append("  - ").append(g.name()).append('\n');
		}
		sendInfo(admin, sb.toString().stripTrailing());
	}

	private void toggleEnabled(Player admin) {
		CustomConfig.UTILITY_AI_ENABLED = !CustomConfig.UTILITY_AI_ENABLED;
		sendInfo(admin, "[Utility AI] enabled = " + CustomConfig.UTILITY_AI_ENABLED + " (session-only)");
	}

	private void disableAll(Player admin) {
		UtilityController.getInstance().disableAll();
		sendInfo(admin, "[Utility AI] kill-switch — whitelist cleared");
	}
}
