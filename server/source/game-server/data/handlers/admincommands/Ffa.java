package admincommands;

import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ffa.FfaModeService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;
import com.aionemu.gameserver.world.World;

/**
 * {@code //ffa} — admin tool for the FFA (Free-For-All) PK feature.
 *
 * <p><b>Subcommands</b>:
 * <ul>
 *   <li>{@code //ffa list} — enumerate players currently in FFA state.</li>
 *   <li>{@code //ffa clear <playerName>} — force a player out of FFA
 *       (stuck-state rescue).</li>
 *   <li>{@code //ffa status} — FFA-prefixed metrics snapshot + active count.</li>
 * </ul>
 *
 * @author BEY_4.8 industrial hardening
 */
public class Ffa extends AdminCommand {

	public Ffa() {
		super("ffa", "FFA admin: list | clear <name> | status | history [n]");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params == null || params.length == 0) {
			sendInfo(admin, "Usage: //ffa list | clear <name> | status | history [n]");
			return;
		}
		String sub = params[0].toLowerCase();
		switch (sub) {
			case "list" -> doList(admin);
			case "clear" -> doClear(admin, params);
			case "status" -> doStatus(admin);
			case "history", "log" -> doHistory(admin, params);
			default -> sendInfo(admin, "Unknown subcommand. //ffa list|clear|status|history");
		}
	}

	private void doList(Player admin) {
		StringBuilder sb = new StringBuilder("Players currently in FFA mode:\n");
		int[] count = {0};
		World.getInstance().forEachPlayer(p -> {
			if (FfaModeService.getInstance().isInFfa(p)) {
				sb.append("  ").append(p.getName())
					.append(" @ worldId=").append(p.getWorldId())
					.append('\n');
				count[0]++;
			}
		});
		if (count[0] == 0)
			sb.append("  (none)\n");
		else
			sb.append("  total: ").append(count[0]).append('\n');
		sendInfo(admin, sb.toString());
	}

	private void doClear(Player admin, String[] params) {
		if (params.length < 2) {
			sendInfo(admin, "Usage: //ffa clear <playerName>");
			return;
		}
		String name = params[1];
		Player target = World.getInstance().getPlayer(name);
		if (target == null) {
			sendInfo(admin, "Player not online: " + name);
			return;
		}
		boolean ok = FfaModeService.getInstance().adminClearFfa(target);
		sendInfo(admin, ok ? "Cleared FFA state on " + name : name + " was not in FFA state.");
		CustomAuditLog.getInstance().logGm("ffa clear", admin.getName(), "target=" + name + " ok=" + ok);
	}

	private void doStatus(Player admin) {
		Map<String, Long> snap = CustomFeatureMetrics.getInstance().snapshot();
		StringBuilder sb = new StringBuilder("FFA metrics:\n");
		boolean any = false;
		for (Map.Entry<String, Long> e : snap.entrySet()) {
			if (e.getKey().startsWith("ffa.") || e.getKey().startsWith("npc.hardcore.")) {
				sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
				any = true;
			}
		}
		if (!any)
			sb.append("  (no counters recorded yet — feature idle or server just booted)\n");
		sb.append("  active_ffa_players = ").append(FfaModeService.getInstance().adminCountActive()).append('\n');
		sendInfo(admin, sb.toString());
	}

	/** Tail the audit log for FFA events (Retrieval dimension). */
	private void doHistory(Player admin, String[] params) {
		int n = 10;
		if (params.length >= 2) {
			try {
				n = Integer.parseInt(params[1]);
			} catch (NumberFormatException ignored) {}
		}
		n = Math.min(n, 50);
		List<String> lines = CustomAuditLog.getInstance().tail(n, "ffa");
		if (lines.isEmpty()) {
			sendInfo(admin, "No FFA audit events recorded.");
			return;
		}
		StringBuilder sb = new StringBuilder("FFA audit (newest first, max ").append(n).append("):\n");
		for (String line : lines) {
			sb.append("  ").append(line).append('\n');
		}
		sendInfo(admin, sb.toString());
	}
}
