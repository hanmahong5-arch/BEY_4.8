package admincommands;

import java.util.List;
import java.util.Map;

import com.aionemu.gameserver.metrics.CustomAuditLog;
import com.aionemu.gameserver.metrics.CustomFeatureMetrics;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.pvpseason.PvpSeasonService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * {@code //season} — admin tool for the PvP Season feature.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code //season status} — current season elapsed/remaining + metrics</li>
 *   <li>{@code //season leaderboard [n]} — top N players (default 10, max 50)</li>
 *   <li>{@code //season archive} — list archived season files</li>
 *   <li>{@code //season rollover} — GM force-end current season</li>
 *   <li>{@code //season history [n]} — tail audit log</li>
 * </ul>
 */
public class Season extends AdminCommand {

	public Season() {
		super("season", "PvP Season admin: status | leaderboard [n] | archive | rollover | history [n]");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params == null || params.length == 0) {
			sendInfo(admin, "Usage: //season status | leaderboard [n] | archive | rollover | history [n]");
			return;
		}
		switch (params[0].toLowerCase()) {
			case "status" -> doStatus(admin);
			case "leaderboard", "lb" -> doLeaderboard(admin, params);
			case "archive" -> doArchive(admin);
			case "rollover" -> doRollover(admin);
			case "history", "log" -> doHistory(admin, params);
			default -> sendInfo(admin, "Unknown. //season status|leaderboard|archive|rollover|history");
		}
	}

	private void doStatus(Player admin) {
		Map<String, Object> snap = PvpSeasonService.getInstance().snapshot();
		StringBuilder sb = new StringBuilder("PvP Season status:\n");
		for (var e : snap.entrySet()) {
			sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
		}
		// Append feature metrics
		Map<String, Long> metrics = CustomFeatureMetrics.getInstance().snapshot();
		sb.append("Metrics:\n");
		for (var e : metrics.entrySet()) {
			if (e.getKey().startsWith("pvpseason."))
				sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
		}
		Map<String, String> timings = CustomFeatureMetrics.getInstance().timingSnapshot();
		for (var e : timings.entrySet()) {
			if (e.getKey().startsWith("pvpseason."))
				sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
		}
		sendInfo(admin, sb.toString());
	}

	private void doLeaderboard(Player admin, String[] params) {
		int n = 10;
		if (params.length >= 2) {
			try { n = Integer.parseInt(params[1]); } catch (NumberFormatException ignored) {}
		}
		n = Math.min(Math.max(1, n), 50);
		List<Object[]> ranking = PvpSeasonService.getInstance().buildLeaderboard(n);
		if (ranking.isEmpty()) {
			sendInfo(admin, "Leaderboard empty — no kills recorded this season yet.");
			return;
		}
		StringBuilder sb = new StringBuilder("PvP Season Leaderboard:\n");
		int rank = 1;
		for (Object[] e : ranking) {
			sb.append(String.format("  #%-3d %-20s K=%-5d D=%-5d AP=%d%n",
				rank++, e[1], e[2], e[3], e[4]));
		}
		sendInfo(admin, sb.toString());
	}

	private void doArchive(Player admin) {
		List<String> files = PvpSeasonService.getInstance().listArchives();
		if (files.isEmpty()) {
			sendInfo(admin, "No archived seasons yet.");
			return;
		}
		StringBuilder sb = new StringBuilder("Archived seasons (newest first):\n");
		for (String f : files) sb.append("  ").append(f).append('\n');
		sendInfo(admin, sb.toString());
	}

	private void doRollover(Player admin) {
		PvpSeasonService.getInstance().adminRollover(admin.getName());
		sendInfo(admin, "Season rolled over. New season started.");
	}

	private void doHistory(Player admin, String[] params) {
		int n = 10;
		if (params.length >= 2) {
			try { n = Integer.parseInt(params[1]); } catch (NumberFormatException ignored) {}
		}
		n = Math.min(n, 50);
		List<String> lines = CustomAuditLog.getInstance().tail(n, "pvpseason");
		if (lines.isEmpty()) {
			sendInfo(admin, "No PvP Season audit events.");
			return;
		}
		StringBuilder sb = new StringBuilder("PvP Season audit (newest first):\n");
		for (String line : lines) sb.append("  ").append(line).append('\n');
		sendInfo(admin, sb.toString());
	}
}
