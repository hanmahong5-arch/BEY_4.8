package admincommands;

import java.util.List;

import com.aionemu.gameserver.metrics.WorldPulse;
import com.aionemu.gameserver.metrics.WorldPulse.RegionEntry;
import com.aionemu.gameserver.metrics.WorldPulse.Snapshot;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * Admin command exposing the WorldPulse metrics bus.
 *
 * <p>Subcommands:
 * <pre>
 *   //pulse              — current snapshot summary
 *   //pulse top [n]      — top N maps by current-interval pheromone deposit (default 5)
 *   //pulse history [n]  — last N snapshots (default 10, max 60)
 * </pre>
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public class Pulse extends AdminCommand {

	private static final int HISTORY_DEFAULT = 10;
	private static final int HISTORY_MAX     = 60;
	private static final int TOP_DEFAULT     = 5;

	public Pulse() {
		super("pulse", "WorldPulse — global server metrics snapshot.");
		setSyntaxInfo(
			"          - current snapshot",
			"<top> [n] - top N maps by pheromone activity (default 5)",
			"<history> [n] - last N snapshots (default 10, max 60)"
		);
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			showCurrent(admin);
			return;
		}
		switch (params[0].toLowerCase()) {
			case "top"     -> showTop(admin, params);
			case "history" -> showHistory(admin, params);
			default        -> showCurrent(admin);
		}
	}

	// -----------------------------------------------------------------------

	private void showCurrent(Player admin) {
		Snapshot s = WorldPulse.getInstance().current();
		StringBuilder sb = new StringBuilder();
		sb.append("[WorldPulse]");
		if (s == null) {
			sb.append(" no samples yet (next sample within 60s)\n");
			sb.append("  pveKillsTotal=").append(WorldPulse.getInstance().pveKillsTotal());
			sb.append("  pvpKillsTotal=").append(WorldPulse.getInstance().pvpKillsTotal());
			sendInfo(admin, sb.toString());
			return;
		}
		long ageSec = (System.currentTimeMillis() - s.tsMillis()) / 1000;
		sb.append(" sampled ").append(ageSec).append("s ago\n");
		sb.append("  online players  : ").append(s.onlinePlayers()).append('\n');
		sb.append("  pveKills total  : ").append(s.pveKillsTotal()).append('\n');
		sb.append("  pvpKills total  : ").append(s.pvpKillsTotal()).append('\n');
		sb.append("  active instances: ").append(s.activeInstances()).append('\n');
		sb.append("  region heat keys: ").append(s.regionHeat() == null ? 0 : s.regionHeat().size());
		sendInfo(admin, sb.toString());
	}

	private void showTop(Player admin, String... params) {
		int n = TOP_DEFAULT;
		if (params.length > 1) {
			try { n = Math.max(1, Math.min(20, Integer.parseInt(params[1]))); }
			catch (NumberFormatException ignored) {}
		}
		List<RegionEntry> top = WorldPulse.getInstance().topRegions(n);
		if (top.isEmpty()) {
			sendInfo(admin, "[WorldPulse Top] no map activity in current interval");
			return;
		}
		StringBuilder sb = new StringBuilder("[WorldPulse Top ").append(n).append("]\n");
		for (RegionEntry e : top) {
			sb.append("  ").append(String.format("%6d", e.heat()))
			  .append("  ").append(e.mapName())
			  .append(" [").append(e.mapId()).append("]\n");
		}
		sendInfo(admin, sb.toString().stripTrailing());
	}

	private void showHistory(Player admin, String... params) {
		int n = HISTORY_DEFAULT;
		if (params.length > 1) {
			try { n = Math.max(1, Math.min(HISTORY_MAX, Integer.parseInt(params[1]))); }
			catch (NumberFormatException ignored) {}
		}
		List<Snapshot> hist = WorldPulse.getInstance().history(n);
		if (hist.isEmpty()) {
			sendInfo(admin, "[WorldPulse History] empty");
			return;
		}
		StringBuilder sb = new StringBuilder("[WorldPulse History] last ").append(hist.size()).append(":\n");
		// Compute deltas between consecutive snapshots for kill rates
		for (int i = 0; i < hist.size(); i++) {
			Snapshot s = hist.get(i);
			Snapshot prev = i + 1 < hist.size() ? hist.get(i + 1) : null;
			long ageSec = (System.currentTimeMillis() - s.tsMillis()) / 1000;
			long pveDelta = prev == null ? 0 : s.pveKillsTotal() - prev.pveKillsTotal();
			long pvpDelta = prev == null ? 0 : s.pvpKillsTotal() - prev.pvpKillsTotal();
			sb.append(String.format("  -%4ds  online=%-3d  ΔpveKills=%-4d  ΔpvpKills=%-3d  insts=%d\n",
				ageSec, s.onlinePlayers(), pveDelta, pvpDelta, s.activeInstances()));
		}
		sendInfo(admin, sb.toString().stripTrailing());
	}
}
