package admincommands;

import java.util.List;

import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.services.achievement.AchievementService;
import com.aionemu.gameserver.services.achievement.AchievementService.Achievement;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;
import com.aionemu.gameserver.world.World;

/**
 * {@code //ach} — admin tool for the Achievement System.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code //ach catalog} — list all achievement definitions</li>
 *   <li>{@code //ach list <playerName>} — show a player's unlocked achievements</li>
 *   <li>{@code //ach grant <playerName> <id>} — GM unlock</li>
 *   <li>{@code //ach revoke <playerName> <id>} — GM remove</li>
 *   <li>{@code //ach history [n]} — audit log tail</li>
 * </ul>
 */
public class Ach extends AdminCommand {

	public Ach() {
		super("ach", "Achievement admin: catalog | list <name> | grant <name> <id> | revoke <name> <id> | history [n]");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params == null || params.length == 0) {
			sendInfo(admin, "Usage: //ach catalog | list <name> | grant <name> <id> | revoke <name> <id> | history [n]");
			return;
		}
		switch (params[0].toLowerCase()) {
			case "catalog", "cat" -> doCatalog(admin);
			case "list" -> doList(admin, params);
			case "grant" -> doGrant(admin, params);
			case "revoke" -> doRevoke(admin, params);
			case "history", "log" -> doHistory(admin, params);
			default -> sendInfo(admin, "Unknown. //ach catalog|list|grant|revoke|history");
		}
	}

	private void doCatalog(Player admin) {
		List<Achievement> cat = AchievementService.getInstance().getCatalog();
		StringBuilder sb = new StringBuilder("Achievement catalog (" + cat.size() + "):\n");
		for (Achievement a : cat) {
			sb.append(String.format("  [%d] %-10s %s (trigger=%s, threshold=%d)%n",
				a.id, a.name, a.desc, a.trigger, a.threshold));
		}
		sendInfo(admin, sb.toString());
	}

	private void doList(Player admin, String[] params) {
		if (params.length < 2) { sendInfo(admin, "Usage: //ach list <playerName>"); return; }
		int playerId = resolvePlayerId(params[1]);
		if (playerId == 0) { sendInfo(admin, "Player not found: " + params[1]); return; }
		List<Achievement> unlocked = AchievementService.getInstance().getUnlocked(playerId);
		if (unlocked.isEmpty()) {
			sendInfo(admin, params[1] + " has no unlocked achievements.");
			return;
		}
		StringBuilder sb = new StringBuilder(params[1] + " has " + unlocked.size() + " achievement(s):\n");
		for (Achievement a : unlocked) {
			sb.append("  [").append(a.id).append("] ").append(a.name).append(" — ").append(a.desc).append('\n');
		}
		sendInfo(admin, sb.toString());
	}

	private void doGrant(Player admin, String[] params) {
		if (params.length < 3) { sendInfo(admin, "Usage: //ach grant <playerName> <id>"); return; }
		int playerId = resolvePlayerId(params[1]);
		if (playerId == 0) { sendInfo(admin, "Player not found: " + params[1]); return; }
		int id;
		try { id = Integer.parseInt(params[2]); }
		catch (NumberFormatException e) { sendInfo(admin, "Achievement id must be numeric."); return; }
		boolean ok = AchievementService.getInstance().adminGrant(playerId, id, admin.getName());
		sendInfo(admin, ok ? "Granted achievement " + id + " to " + params[1]
			: "Already unlocked or invalid id.");
	}

	private void doRevoke(Player admin, String[] params) {
		if (params.length < 3) { sendInfo(admin, "Usage: //ach revoke <playerName> <id>"); return; }
		int playerId = resolvePlayerId(params[1]);
		if (playerId == 0) { sendInfo(admin, "Player not found: " + params[1]); return; }
		int id;
		try { id = Integer.parseInt(params[2]); }
		catch (NumberFormatException e) { sendInfo(admin, "Achievement id must be numeric."); return; }
		boolean ok = AchievementService.getInstance().adminRevoke(playerId, id, admin.getName());
		sendInfo(admin, ok ? "Revoked achievement " + id + " from " + params[1]
			: "Not unlocked or invalid id.");
	}

	private void doHistory(Player admin, String[] params) {
		int n = 10;
		if (params.length >= 2) {
			try { n = Integer.parseInt(params[1]); } catch (NumberFormatException ignored) {}
		}
		n = Math.min(n, 50);
		List<String> lines = CustomAuditLog.getInstance().tail(n, "achievement");
		if (lines.isEmpty()) { sendInfo(admin, "No achievement events."); return; }
		StringBuilder sb = new StringBuilder("Achievement audit (newest first):\n");
		for (String line : lines) sb.append("  ").append(line).append('\n');
		sendInfo(admin, sb.toString());
	}

	private static int resolvePlayerId(String name) {
		Player online = World.getInstance().getPlayer(name);
		if (online != null) return online.getObjectId();
		PlayerCommonData pcd = PlayerService.getOrLoadPlayerCommonData(name);
		return pcd != null ? pcd.getPlayerObjId() : 0;
	}
}
