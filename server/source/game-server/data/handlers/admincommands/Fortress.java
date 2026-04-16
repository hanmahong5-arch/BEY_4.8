package admincommands;

import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.siege.FortressLocation;
import com.aionemu.gameserver.services.SiegeService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.siege.SoloFortressService;
import com.aionemu.gameserver.utils.BroadcastUtil;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;
import com.aionemu.gameserver.world.World;

/**
 * {@code //fortress} — admin tool for the solo-fortress feature.
 *
 * <p><b>Subcommands</b>:
 * <ul>
 *   <li>{@code //fortress list} — dump every fortress with its current owner
 *       (race, legion id, solo player name).</li>
 *   <li>{@code //fortress reset <id>} — force-clear solo ownership for the
 *       given fortress id; persists to DB immediately and broadcasts.</li>
 *   <li>{@code //fortress status} — show feature metrics snapshot: capture
 *       count, dethrone count, bounty awarded/denied, tax paid totals, decays.</li>
 * </ul>
 *
 * @author BEY_4.8 industrial hardening
 */
public class Fortress extends AdminCommand {

	public Fortress() {
		super("fortress", "Solo-fortress admin: list | reset <id> | status | leaderboard | grant <player> <id> | history [n]");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params == null || params.length == 0) {
			sendInfo(admin, "Usage: //fortress list | reset <id> | status | leaderboard | grant <player> <id> | history [n]");
			return;
		}
		String sub = params[0].toLowerCase();
		switch (sub) {
			case "list" -> doList(admin);
			case "reset" -> doReset(admin, params);
			case "status" -> doStatus(admin);
			case "leaderboard", "lb" -> doLeaderboard(admin);
			case "grant" -> doGrant(admin, params);
			case "history", "log" -> doHistory(admin, params);
			default -> sendInfo(admin, "Unknown subcommand. //fortress list|reset|status|leaderboard|grant|history");
		}
	}

	private void doList(Player admin) {
		StringBuilder sb = new StringBuilder("Fortress ownership:\n");
		Map<Integer, FortressLocation> fortresses = SiegeService.getInstance().getFortresses();
		for (FortressLocation f : fortresses.values()) {
			String name = f.getTemplate() == null ? "#" + f.getLocationId()
				: (f.getTemplate().getL10n() == null ? "#" + f.getLocationId() : f.getTemplate().getL10n().toString());
			String ownerDesc;
			if (f.getOwnerPlayerId() != 0) {
				PlayerCommonData pcd = PlayerService.getOrLoadPlayerCommonData(f.getOwnerPlayerId());
				ownerDesc = "SOLO " + (pcd != null ? pcd.getName() : "#" + f.getOwnerPlayerId());
			} else if (f.getLegionId() != 0) {
				ownerDesc = "legion #" + f.getLegionId();
			} else {
				ownerDesc = f.getRace().toString();
			}
			sb.append("  [").append(f.getLocationId()).append("] ").append(name)
				.append(" → ").append(ownerDesc)
				.append(" (tier ").append(f.getOccupiedCount()).append(")\n");
		}
		sendInfo(admin, sb.toString());
	}

	private void doReset(Player admin, String[] params) {
		if (params.length < 2) {
			sendInfo(admin, "Usage: //fortress reset <fortressId>");
			return;
		}
		int id;
		try {
			id = Integer.parseInt(params[1]);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Fortress id must be numeric.");
			return;
		}
		boolean ok = SoloFortressService.getInstance().adminResetFortress(id);
		sendInfo(admin, ok ? "Fortress " + id + " ownership cleared." : "Fortress " + id + " not found.");
		CustomAuditLog.getInstance().logGm("fortress reset", admin.getName(), "id=" + id + " ok=" + ok);
	}

	private void doStatus(Player admin) {
		Map<String, Long> snap = CustomFeatureMetrics.getInstance().snapshot();
		StringBuilder sb = new StringBuilder("Solo-fortress metrics:\n");
		boolean any = false;
		for (Map.Entry<String, Long> e : snap.entrySet()) {
			if (e.getKey().startsWith("fortress.")) {
				sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
				any = true;
			}
		}
		if (!any)
			sb.append("  (no counters recorded yet — feature idle or server just booted)\n");

		// Timing stats (sweep duration)
		Map<String, String> timings = CustomFeatureMetrics.getInstance().timingSnapshot();
		for (Map.Entry<String, String> t : timings.entrySet()) {
			if (t.getKey().startsWith("fortress."))
				sb.append("  ").append(t.getKey()).append(" = ").append(t.getValue()).append('\n');
		}

		// Live count
		int soloCount = 0;
		for (FortressLocation f : SiegeService.getInstance().getFortresses().values()) {
			if (f.getOwnerPlayerId() != 0)
				soloCount++;
		}
		sb.append("  active_solo_owned = ").append(soloCount).append('\n');
		sendInfo(admin, sb.toString());
	}

	/** Show top fortress lords by owned count (Retrieval + Product dimension). */
	private void doLeaderboard(Player admin) {
		List<Object[]> lb = SoloFortressService.getInstance().getLeaderboard();
		if (lb.isEmpty()) {
			sendInfo(admin, "No fortress lords at this time.");
			return;
		}
		StringBuilder sb = new StringBuilder("Fortress Leaderboard:\n");
		int rank = 1;
		for (Object[] entry : lb) {
			String name = (String) entry[0];
			int count = (Integer) entry[1];
			String fortresses = (String) entry[2];
			sb.append("  #").append(rank++).append(" ")
				.append(BroadcastUtil.lordTitle(name, count))
				.append(" — ").append(count).append("座: ").append(fortresses).append('\n');
			if (rank > 10)
				break;
		}
		sendInfo(admin, sb.toString());
	}

	/** GM direct-grant fortress ownership (Tool contract dimension). */
	private void doGrant(Player admin, String[] params) {
		if (params.length < 3) {
			sendInfo(admin, "Usage: //fortress grant <playerName> <fortressId>");
			return;
		}
		String playerName = params[1];
		int id;
		try {
			id = Integer.parseInt(params[2]);
		} catch (NumberFormatException e) {
			sendInfo(admin, "Fortress id must be numeric.");
			return;
		}
		// Try online player first, fall back to DB lookup
		Player target = World.getInstance().getPlayer(playerName);
		int objId;
		if (target != null) {
			objId = target.getObjectId();
		} else {
			PlayerCommonData pcd = PlayerService.getOrLoadPlayerCommonData(playerName);
			if (pcd == null) {
				sendInfo(admin, "Player not found: " + playerName);
				return;
			}
			objId = pcd.getPlayerObjId();
		}
		boolean ok = SoloFortressService.getInstance().adminGrantFortress(id, objId, playerName);
		sendInfo(admin, ok ? "Granted fortress " + id + " to " + playerName : "Fortress " + id + " not found.");
		CustomAuditLog.getInstance().logGm("fortress grant", admin.getName(),
			"target=" + playerName + " fortress=" + id + " ok=" + ok);
	}

	/** Tail the audit log for fortress events (Retrieval dimension). */
	private void doHistory(Player admin, String[] params) {
		int n = 10;
		if (params.length >= 2) {
			try {
				n = Integer.parseInt(params[1]);
			} catch (NumberFormatException ignored) {}
		}
		n = Math.min(n, 50); // cap to prevent huge output
		List<String> lines = CustomAuditLog.getInstance().tail(n, "fortress");
		if (lines.isEmpty()) {
			sendInfo(admin, "No fortress audit events recorded.");
			return;
		}
		StringBuilder sb = new StringBuilder("Fortress audit (newest first, max ").append(n).append("):\n");
		for (String line : lines) {
			sb.append("  ").append(line).append('\n');
		}
		sendInfo(admin, sb.toString());
	}
}
