package admincommands;

import java.util.List;

import com.aionemu.gameserver.ai.swarm.SwarmPheromoneGrid;
import com.aionemu.gameserver.ai.swarm.debug.SwarmTelemetry;
import com.aionemu.gameserver.ai.swarm.debug.SwarmTelemetry.Decision;
import com.aionemu.gameserver.ai.swarm.debug.SwarmTelemetry.Snapshot;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

/**
 * Admin command exposing the swarm-intelligence observability layer.
 *
 * <p>Subcommands:
 * <pre>
 *   //swarm heat           — pheromone heat matrix around caller
 *   //swarm target         — attention score breakdown of caller's current target
 *   //swarm pulse          — performance counters + perf percentiles
 *   //swarm decisions [n]  — last N target-switch decisions (default 10, max 50)
 *   //swarm toggle         — toggle SWARM_DEBUG_ENABLED (session-only)
 *   //swarm reset          — reset telemetry counters
 *   //swarm deposit <int>  — force-deposit pheromone at caller's position (test tool)
 * </pre>
 *
 * <p>All subcommands are read-only except {@code toggle}, {@code reset}, and
 * {@code deposit}, which only affect dev/debug state.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public class Swarm extends AdminCommand {

	private static final int HEAT_RADIUS_CELLS = 3;   // 3 cells each side → 7x7 matrix
	private static final int DECISIONS_DEFAULT = 10;
	private static final int DECISIONS_MAX     = 50;

	public Swarm() {
		super("swarm", "Swarm intelligence observability — pheromone grid and multi-head attention scorer.");

		// @formatter:off
		setSyntaxInfo(
			"<heat>          - 7x7 pheromone matrix around you",
			"<target>        - 5-head attention score breakdown of your target",
			"<pulse>         - performance counters + p50/p99 timing",
			"<decisions> [n] - last N target-switch decisions (default 10)",
			"<toggle>        - toggle debug instrumentation (session-only)",
			"<reset>         - clear telemetry counters",
			"<deposit> <n>   - force-deposit n pheromone at your position (testing)"
		);
		// @formatter:on
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length == 0) {
			sendInfo(admin);
			return;
		}
		String sub = params[0].toLowerCase();
		switch (sub) {
			case "heat"      -> showHeat(admin);
			case "target"    -> showTarget(admin);
			case "pulse"     -> showPulse(admin);
			case "decisions" -> showDecisions(admin, params);
			case "toggle"    -> toggleDebug(admin);
			case "reset"     -> resetTelemetry(admin);
			case "deposit"   -> forceDeposit(admin, params);
			default          -> sendInfo(admin);
		}
	}

	// -----------------------------------------------------------------------
	// //swarm heat — pheromone heat matrix
	// -----------------------------------------------------------------------

	private void showHeat(Player admin) {
		SwarmPheromoneGrid grid = SwarmPheromoneGrid.getInstance();
		float cx = admin.getX(), cy = admin.getY();
		int mapId = admin.getWorldId();

		StringBuilder sb = new StringBuilder();
		sb.append("[Swarm Heat] pos=(").append(fmt(cx)).append(", ").append(fmt(cy))
		  .append(") map=").append(mapId).append('\n');
		sb.append("cell size = ").append(SwarmPheromoneGrid.CELL_SIZE).append(" wu\n");

		int cellSize = SwarmPheromoneGrid.CELL_SIZE;
		int totalDeposits = 0, totalMass = 0;

		// North at top — rows go -R..+R (dy), cols go -R..+R (dx)
		for (int dy = HEAT_RADIUS_CELLS; dy >= -HEAT_RADIUS_CELLS; dy--) {
			for (int dx = -HEAT_RADIUS_CELLS; dx <= HEAT_RADIUS_CELLS; dx++) {
				float sx = cx + dx * cellSize;
				float sy = cy + dy * cellSize;
				int v = grid.sample(sx, sy, mapId);
				if (v > 0) {
					totalDeposits++;
					totalMass += v;
				}
				String mark = (dx == 0 && dy == 0) ? markCenter(v) : markCell(v);
				sb.append(mark);
			}
			sb.append('\n');
		}
		sb.append("occupied cells: ").append(totalDeposits)
		  .append("   total mass: ").append(totalMass);
		sendInfo(admin, sb.toString());
	}

	/** Returns a 4-char marker for a non-center cell. */
	private static String markCell(int intensity) {
		if (intensity == 0)                                            return " .  ";
		if (intensity < SwarmPheromoneGrid.THRESHOLD_ALERT)            return " ·  ";
		if (intensity < SwarmPheromoneGrid.THRESHOLD_RETURN)           return " +  ";
		if (intensity < SwarmPheromoneGrid.THRESHOLD_PERSIST)          return " *  ";
		if (intensity < SwarmPheromoneGrid.DEPOSIT_DEATH)              return " #  ";
		return " @  ";
	}

	/** Center cell gets a P marker and shows raw value. */
	private static String markCenter(int intensity) {
		return "P" + String.format("%3d", Math.min(999, intensity));
	}

	// -----------------------------------------------------------------------
	// //swarm target — attention breakdown of current target
	// -----------------------------------------------------------------------

	private void showTarget(Player admin) {
		VisibleObject tgt = admin.getTarget();
		if (!(tgt instanceof Npc npc)) {
			sendInfo(admin, "Select an NPC target first.");
			return;
		}

		StringBuilder sb = new StringBuilder();
		sb.append("[Swarm AI] ").append(npc.getName())
		  .append(" [obj=").append(npc.getObjectId()).append("]\n");
		long cur = npc.getLifeStats().getCurrentHp();
		long max = npc.getLifeStats().getMaxHp();
		int hpPct = max <= 0 ? 100 : (int) ((long) cur * 100 / max);
		sb.append("state=").append(npc.getAi().getState())
		  .append(" sub=").append(npc.getAi().getSubState())
		  .append("  hp=").append(cur).append('/').append(max)
		  .append(" (").append(hpPct).append("%)\n");
		sb.append("tribe=").append(npc.getTribe())
		  .append("  pos=(").append(fmt(npc.getX())).append(", ").append(fmt(npc.getY())).append(")\n");

		int localPheromone = SwarmPheromoneGrid.getInstance().sample(
			npc.getX(), npc.getY(), npc.getWorldId());
		sb.append("pheromone@pos=").append(localPheromone);
		sb.append(" (alert=").append(localPheromone >= SwarmPheromoneGrid.THRESHOLD_ALERT);
		sb.append(" persist=").append(localPheromone >= SwarmPheromoneGrid.THRESHOLD_PERSIST).append(")\n");

		// Aggro list + attention scores
		var entries = npc.getAggroList().stream()
			.filter(e -> e.getHate() > 0 && !e.getAttacker().isDead())
			.toList();
		if (entries.isEmpty()) {
			sb.append("aggro list: empty\n");
		} else {
			sb.append("aggro list (").append(entries.size()).append(" targets):\n");
			for (var e : entries) {
				Creature c = e.getAttacker();
				int ph = SwarmPheromoneGrid.getInstance().sample(c.getX(), c.getY(), c.getWorldId());
				float score = com.aionemu.gameserver.ai.swarm.NpcAttentionScorer.score(
					npc, c, e.getHate(), ph);
				long chp = c.getLifeStats().getCurrentHp();
				long cmx = c.getLifeStats().getMaxHp();
				int chpPct = cmx <= 0 ? 100 : (int) ((long) chp * 100 / cmx);
				sb.append("  ").append(padRight(c.getName(), 16))
				  .append(" hate=").append(String.format("%6d", e.getHate()))
				  .append(" hp=").append(String.format("%3d%%", chpPct))
				  .append(" ph=").append(String.format("%4d", ph))
				  .append(" → score=").append(String.format("%.3f", score))
				  .append('\n');
			}
		}

		// Show current target
		Creature curTgt = npc.getTarget() instanceof Creature cc ? cc : null;
		sb.append("current target: ").append(curTgt == null ? "<none>" : curTgt.getName());
		sendInfo(admin, sb.toString());
	}

	// -----------------------------------------------------------------------
	// //swarm pulse — performance counters
	// -----------------------------------------------------------------------

	private void showPulse(Player admin) {
		if (!CustomConfig.SWARM_DEBUG_ENABLED) {
			sendInfo(admin, "[Swarm Pulse] debug is OFF — //swarm toggle to enable (counters show zeros)");
		}
		Snapshot s = SwarmTelemetry.getInstance().snapshot();
		StringBuilder sb = new StringBuilder();
		sb.append("[Swarm Pulse]\n");
		sb.append("  thinkAttack calls    : ").append(s.thinkAttackCalls()).append('\n');
		sb.append("  reevaluate calls     : ").append(s.reevaluateCalls()).append('\n');
		sb.append("  target switches      : ").append(s.targetSwitches());
		if (s.reevaluateCalls() > 0) {
			double rate = 100.0 * s.targetSwitches() / s.reevaluateCalls();
			sb.append(String.format("  (%.1f%% switch rate)", rate));
		}
		sb.append('\n');
		sb.append("  investigate calls    : ").append(s.investigateCalls()).append('\n');
		sb.append("  investigate hits     : ").append(s.investigateHits()).append('\n');
		sb.append("  pheromone deposits   : ").append(s.pheromoneDeposits()).append('\n');
		sb.append("  distress broadcasts  : ").append(s.distressBroadcasts()).append('\n');
		sb.append("  disengage suppressed : ").append(s.disengageSuppressed()).append('\n');
		sb.append("  return suppressed    : ").append(s.returnSuppressed()).append('\n');
		sb.append("  reevaluate p50       : ").append(fmtNanos(s.thinkAttackP50Ns())).append('\n');
		sb.append("  reevaluate p99       : ").append(fmtNanos(s.thinkAttackP99Ns()));
		sendInfo(admin, sb.toString());
	}

	// -----------------------------------------------------------------------
	// //swarm decisions [n] — recent target switches
	// -----------------------------------------------------------------------

	private void showDecisions(Player admin, String... params) {
		int n = DECISIONS_DEFAULT;
		if (params.length > 1) {
			try {
				n = Math.min(DECISIONS_MAX, Math.max(1, Integer.parseInt(params[1])));
			} catch (NumberFormatException ignored) {}
		}
		List<Decision> list = SwarmTelemetry.getInstance().recentDecisions(n);
		if (list.isEmpty()) {
			sendInfo(admin, "[Swarm Decisions] none recorded"
				+ (CustomConfig.SWARM_DEBUG_ENABLED ? "" : " (debug is OFF)"));
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("[Swarm Decisions] last ").append(list.size()).append(":\n");
		for (Decision d : list) {
			sb.append("  ").append(padRight(d.npcName(), 14))
			  .append(" ").append(padRight(d.oldTarget(), 14))
			  .append(" → ").append(padRight(d.newTarget(), 14))
			  .append(" Δ=").append(String.format("%.3f", d.newScore() - d.oldScore()))
			  .append('\n');
		}
		sendInfo(admin, sb.toString().stripTrailing());
	}

	// -----------------------------------------------------------------------
	// //swarm toggle / reset / deposit — state mutation (debug-only)
	// -----------------------------------------------------------------------

	private void toggleDebug(Player admin) {
		CustomConfig.SWARM_DEBUG_ENABLED = !CustomConfig.SWARM_DEBUG_ENABLED;
		sendInfo(admin, "SWARM_DEBUG_ENABLED = " + CustomConfig.SWARM_DEBUG_ENABLED
			+ " (session-only; restart reverts to config file)");
	}

	private void resetTelemetry(Player admin) {
		SwarmTelemetry.getInstance().reset();
		sendInfo(admin, "Swarm telemetry counters cleared.");
	}

	private void forceDeposit(Player admin, String... params) {
		int intensity = SwarmPheromoneGrid.DEPOSIT_COMBAT;
		if (params.length > 1) {
			try { intensity = Integer.parseInt(params[1]); } catch (NumberFormatException ignored) {}
		}
		SwarmPheromoneGrid.getInstance().deposit(admin.getX(), admin.getY(), admin.getWorldId(), intensity);
		sendInfo(admin, "Deposited " + intensity + " pheromone at your position.");
	}

	// -----------------------------------------------------------------------
	// Formatting helpers
	// -----------------------------------------------------------------------

	private static String fmt(float v) { return String.format("%.1f", v); }

	private static String padRight(String s, int width) {
		if (s == null) s = "<none>";
		if (s.length() >= width) return s.substring(0, width);
		return s + " ".repeat(width - s.length());
	}

	private static String fmtNanos(long ns) {
		if (ns <= 0) return "(no samples)";
		if (ns < 1_000) return ns + " ns";
		if (ns < 1_000_000) return String.format("%.1f μs", ns / 1_000.0);
		return String.format("%.2f ms", ns / 1_000_000.0);
	}
}
