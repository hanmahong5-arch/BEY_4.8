package com.aionemu.gameserver.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for the WorldPulse counter API and ring-buffer semantics.
 *
 * <p>We don't test the scheduled-sampling loop here (it's bound to the
 * game's ThreadPoolManager which has heavy startup deps). Instead we verify
 * the parts that are pure data: counters, region-heat aggregation, and
 * the ring-buffer query API.
 *
 * @author SwarmIntelligence / BEY_4.8
 */
class WorldPulseRingBufferTest {

	@Test
	void recordPveKill_incrementsCounter() {
		WorldPulse pulse = WorldPulse.getInstance();
		long before = pulse.pveKillsTotal();
		pulse.recordPveKill();
		pulse.recordPveKill();
		pulse.recordPveKill();
		assertEquals(before + 3, pulse.pveKillsTotal());
	}

	@Test
	void recordPvpKill_incrementsCounter() {
		WorldPulse pulse = WorldPulse.getInstance();
		long before = pulse.pvpKillsTotal();
		pulse.recordPvpKill();
		assertEquals(before + 1, pulse.pvpKillsTotal());
	}

	@Test
	void recordPheromoneDeposit_buildsRegionHeat() {
		WorldPulse pulse = WorldPulse.getInstance();
		// Two distinct test maps — must not interfere with anything real
		int mapA = 990001, mapB = 990002;
		for (int i = 0; i < 10; i++) pulse.recordPheromoneDeposit(mapA);
		for (int i = 0; i < 3; i++)  pulse.recordPheromoneDeposit(mapB);
		List<WorldPulse.RegionEntry> top = pulse.topRegions(5);
		// Find our maps
		int heatA = top.stream().filter(e -> e.mapId() == mapA).mapToInt(WorldPulse.RegionEntry::heat).findFirst().orElse(0);
		int heatB = top.stream().filter(e -> e.mapId() == mapB).mapToInt(WorldPulse.RegionEntry::heat).findFirst().orElse(0);
		assertTrue(heatA >= 10, "mapA should have at least 10 deposits");
		assertTrue(heatB >= 3,  "mapB should have at least 3 deposits");
	}

	@Test
	void topRegions_isSortedDescending() {
		WorldPulse pulse = WorldPulse.getInstance();
		pulse.recordPheromoneDeposit(990010);
		for (int i = 0; i < 5; i++) pulse.recordPheromoneDeposit(990011);
		for (int i = 0; i < 50; i++) pulse.recordPheromoneDeposit(990012);
		List<WorldPulse.RegionEntry> top = pulse.topRegions(3);
		// Top entries must be in descending order
		for (int i = 1; i < top.size(); i++) {
			assertTrue(top.get(i - 1).heat() >= top.get(i).heat(),
				"topRegions must be sorted descending; got " + top.get(i - 1).heat() + " before " + top.get(i).heat());
		}
	}

	@Test
	void topRegions_respectsLimit() {
		WorldPulse pulse = WorldPulse.getInstance();
		for (int i = 0; i < 10; i++) pulse.recordPheromoneDeposit(990020 + i);
		List<WorldPulse.RegionEntry> top = pulse.topRegions(3);
		assertTrue(top.size() <= 3);
	}
}
