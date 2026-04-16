package com.aionemu.gameserver.metrics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * WorldPulse — single-fact-source for live world metrics.
 *
 * <p>Collects PvE kill counts, PvP kill counts, online player count, active
 * instance count, and per-map pheromone deposit rates. Samples every minute
 * into an in-memory 24-hour ring buffer, flushes to PostgreSQL every 5
 * minutes.
 *
 * <p>Thread-safety: all counters are {@link AtomicLong}; the per-map heat
 * counter uses {@link ConcurrentHashMap}. Snapshots are constructed as
 * immutable records so callers can share them freely.
 *
 * <p>Initialisation is lazy: the first {@link #getInstance()} call triggers
 * table creation + scheduling. Call sites never need to worry about startup
 * ordering.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
public final class WorldPulse {

	private static final Logger log = LoggerFactory.getLogger(WorldPulse.class);

	private static final int  HISTORY_SIZE       = 1440;    // 24 hours @ 1 sample / min
	private static final long SAMPLE_INTERVAL_MS = 60_000L;   // 1 minute
	private static final long FLUSH_INTERVAL_MS  = 300_000L;  // 5 minutes

	private static final String DDL = """
		CREATE TABLE IF NOT EXISTS world_pulse (
			ts              TIMESTAMPTZ PRIMARY KEY,
			online_players  INT NOT NULL,
			pve_kills_total BIGINT NOT NULL,
			pvp_kills_total BIGINT NOT NULL,
			active_insts    INT NOT NULL,
			region_heat     TEXT
		)
		""";

	private static final String DDL_IDX = "CREATE INDEX IF NOT EXISTS idx_world_pulse_ts ON world_pulse (ts DESC)";

	private static final String INSERT =
		"INSERT INTO world_pulse (ts, online_players, pve_kills_total, pvp_kills_total, active_insts, region_heat) "
		+ "VALUES (to_timestamp(? / 1000.0), ?, ?, ?, ?, ?) ON CONFLICT (ts) DO NOTHING";

	private static final WorldPulse INSTANCE = new WorldPulse();
	public static WorldPulse getInstance() { return INSTANCE; }

	// --- cumulative counters (lifetime of process) ---
	private final AtomicLong pveKillsTotal = new AtomicLong();
	private final AtomicLong pvpKillsTotal = new AtomicLong();

	// --- current-interval per-map pheromone deposit counter (reset each sample) ---
	private final ConcurrentHashMap<Integer, AtomicLong> currentIntervalHeat = new ConcurrentHashMap<>();

	// --- in-memory history ring buffer ---
	private final Snapshot[] history = new Snapshot[HISTORY_SIZE];
	private volatile long sampleIdx = 0;

	// --- pending writes to be flushed ---
	private final java.util.concurrent.ConcurrentLinkedQueue<Snapshot> pendingWrites = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private volatile boolean initialized = false;

	private WorldPulse() {
		// Lazy init — scheduled on first getInstance() via initIfNeeded()
		initIfNeeded();
	}

	private synchronized void initIfNeeded() {
		if (initialized) return;
		try {
			createTableIfMissing();
		} catch (Exception e) {
			log.error("WorldPulse: failed to create world_pulse table — continuing without persistence", e);
		}
		ThreadPoolManager.getInstance().scheduleAtFixedRate(this::sampleTick, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS);
		ThreadPoolManager.getInstance().scheduleAtFixedRate(this::flushTick, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS);
		initialized = true;
		log.info("WorldPulse initialized (sample={}ms, flush={}ms, history={} slots)",
			SAMPLE_INTERVAL_MS, FLUSH_INTERVAL_MS, HISTORY_SIZE);
	}

	// -----------------------------------------------------------------------
	// Record API — called from hooks
	// -----------------------------------------------------------------------

	/** Record a PvE kill (NPC killed by player or player's pet/summon). */
	public void recordPveKill() {
		pveKillsTotal.incrementAndGet();
	}

	/** Record a PvP kill (player killed by another player). */
	public void recordPvpKill() {
		pvpKillsTotal.incrementAndGet();
	}

	/** Record a pheromone deposit event — feeds RegionHeat. */
	public void recordPheromoneDeposit(int mapId) {
		currentIntervalHeat.computeIfAbsent(mapId, k -> new AtomicLong()).incrementAndGet();
	}

	// -----------------------------------------------------------------------
	// Query API — read by //pulse admin command
	// -----------------------------------------------------------------------

	/** Returns the most recent snapshot, or null if none sampled yet. */
	public Snapshot current() {
		long idx = sampleIdx;
		if (idx == 0) return null;
		return history[(int) ((idx - 1) % HISTORY_SIZE)];
	}

	/** Returns up to {@code n} most recent snapshots, newest first. */
	public List<Snapshot> history(int n) {
		int cap = Math.min(n, HISTORY_SIZE);
		List<Snapshot> out = new ArrayList<>(cap);
		long idx = sampleIdx;
		for (int k = 0; k < cap; k++) {
			long ri = idx - 1 - k;
			if (ri < 0) break;
			Snapshot s = history[(int) (ri % HISTORY_SIZE)];
			if (s == null) break;
			out.add(s);
		}
		return out;
	}

	/** Returns the top-N maps by current-interval heat, descending. */
	public List<RegionEntry> topRegions(int n) {
		Map<Integer, Integer> snapshot = currentIntervalHeatSnapshot();
		return snapshot.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(n)
			.map(e -> new RegionEntry(e.getKey(), mapName(e.getKey()), e.getValue()))
			.toList();
	}

	/** Returns a live snapshot of PvE/PvP kill totals. */
	public long pveKillsTotal() { return pveKillsTotal.get(); }
	public long pvpKillsTotal() { return pvpKillsTotal.get(); }

	// -----------------------------------------------------------------------
	// Internal: sampling loop
	// -----------------------------------------------------------------------

	private void sampleTick() {
		try {
			Map<Integer, Integer> heat = snapshotAndClearIntervalHeat();
			Snapshot s = new Snapshot(
				System.currentTimeMillis(),
				onlinePlayerCount(),
				pveKillsTotal.get(),
				pvpKillsTotal.get(),
				countActiveInstances(),
				heat
			);
			long myIdx = sampleIdx;
			history[(int) (myIdx % HISTORY_SIZE)] = s;
			sampleIdx = myIdx + 1;
			pendingWrites.offer(s);
			// Cap the pending queue defensively (DB down scenario)
			while (pendingWrites.size() > 200) pendingWrites.poll();
		} catch (Exception e) {
			log.warn("WorldPulse sample error", e);
		}
	}

	private void flushTick() {
		List<Snapshot> batch = new ArrayList<>();
		Snapshot s;
		while ((s = pendingWrites.poll()) != null) batch.add(s);
		if (batch.isEmpty()) return;
		try (Connection con = DatabaseFactory.getConnection();
		     PreparedStatement ps = con.prepareStatement(INSERT)) {
			for (Snapshot snap : batch) {
				ps.setLong(1, snap.tsMillis());
				ps.setInt(2, snap.onlinePlayers());
				ps.setLong(3, snap.pveKillsTotal());
				ps.setLong(4, snap.pvpKillsTotal());
				ps.setInt(5, snap.activeInstances());
				ps.setString(6, encodeHeatMap(snap.regionHeat()));
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			log.warn("WorldPulse flush error ({} rows dropped)", batch.size(), e);
		}
	}

	private void createTableIfMissing() throws SQLException {
		try (Connection con = DatabaseFactory.getConnection();
		     Statement stmt = con.createStatement()) {
			stmt.execute(DDL);
			stmt.execute(DDL_IDX);
		}
	}

	// -----------------------------------------------------------------------
	// Internal: collection helpers
	// -----------------------------------------------------------------------

	private static int onlinePlayerCount() {
		return World.getInstance().getAllPlayers().size();
	}

	private static int countActiveInstances() {
		// Count WorldMapInstance objects across all maps. WorldMapsData implements
		// Iterable<WorldMapTemplate> and WorldMap implements Iterable<WorldMapInstance>.
		// Typically < 100 world maps, so this is O(num_maps + num_instances).
		if (DataManager.WORLD_MAPS_DATA == null) return 0;
		int count = 0;
		for (WorldMapTemplate t : DataManager.WORLD_MAPS_DATA) {
			var map = World.getInstance().getWorldMap(t.getMapId());
			if (map == null) continue;
			for (var ignored : map) count++;
		}
		return count;
	}

	private Map<Integer, Integer> snapshotAndClearIntervalHeat() {
		Map<Integer, Integer> out = new java.util.HashMap<>();
		currentIntervalHeat.forEach((k, v) -> {
			int snapshot = (int) v.getAndSet(0);
			if (snapshot > 0) out.put(k, snapshot);
		});
		return out;
	}

	private Map<Integer, Integer> currentIntervalHeatSnapshot() {
		Map<Integer, Integer> out = new java.util.HashMap<>();
		currentIntervalHeat.forEach((k, v) -> {
			int snapshot = (int) v.get();
			if (snapshot > 0) out.put(k, snapshot);
		});
		return out;
	}

	private static String mapName(int mapId) {
		// Guard against unit-test execution where DataManager isn't bootstrapped
		if (DataManager.WORLD_MAPS_DATA == null) return "map_" + mapId;
		WorldMapTemplate t = DataManager.WORLD_MAPS_DATA.getTemplate(mapId);
		return t != null ? t.getName() : "map_" + mapId;
	}

	/** Encode the heat map as CSV: `mapId:count,mapId:count,...` for human-friendly DB storage. */
	private static String encodeHeatMap(Map<Integer, Integer> heat) {
		if (heat == null || heat.isEmpty()) return null;
		StringBuilder sb = new StringBuilder();
		heat.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(20)
			.forEach(e -> {
				if (sb.length() > 0) sb.append(',');
				sb.append(e.getKey()).append(':').append(e.getValue());
			});
		return sb.toString();
	}

	// -----------------------------------------------------------------------
	// DTOs
	// -----------------------------------------------------------------------

	public record Snapshot(
		long tsMillis,
		int onlinePlayers,
		long pveKillsTotal,
		long pvpKillsTotal,
		int activeInstances,
		Map<Integer, Integer> regionHeat
	) {}

	public record RegionEntry(int mapId, String mapName, int heat) {}
}
