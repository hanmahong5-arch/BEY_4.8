package com.aionemu.gameserver.services;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerLuckState;

/**
 * Luck System v2 — momentum-based "flow luck" (心流幸运).
 *
 * Luck is NOT a consumable resource. It is a MOMENTUM value that rises on
 * failure and resets on success, creating natural pity protection. Players
 * FEEL it but don't need to MANAGE it.
 *
 * Design matches human cognitive systems:
 * - Dopamine: luck rising after failure = "next time!"
 * - Serotonin: pity guarantee = no despair
 * - Oxytocin: pet presence = lucky charm attachment
 * - Cortisol: NO supplement management = reduced anxiety
 *
 * FP infrastructure remapping:
 * - MaxFP (from wings/gear) → luck CEILING (higher cap for pity buildup)
 * - FP potions → instant luck +20% boost
 * - FP natural regen → luck natural drift toward 50%
 * - Pet summoned → luck FLOOR raised (35% vs 20%)
 *
 * What this REPLACES:
 * - Supplements are no longer needed (luck is the new supplement)
 * - FP as flight resource is dead (FREE_FLIGHT=true)
 * - No resource management, no optimization anxiety
 */
public class LuckService {

	// Luck increment per failure type
	private static final float ENCHANT_FAIL_INCREMENT = 0.08f;
	private static final float SOCKET_FAIL_INCREMENT = 0.05f;
	private static final float CRAFT_FAIL_INCREMENT = 0.03f;

	// Luck floors
	private static final float FLOOR_NO_PET = 0.20f;
	private static final float FLOOR_WITH_PET = 0.35f;

	// FP potion luck boost
	private static final float POTION_BOOST = 0.20f;

	/**
	 * Get the current luck bonus percentage for a player.
	 * Factors in momentum, pity, pet, and FP gear ceiling.
	 *
	 * @return bonus in percentage points (e.g., 12.5 means +12.5%)
	 */
	public static float getLuckBonus(Player player) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED)
			return 0f;
		PlayerLuckState state = player.getLuckState();
		float floor = getFloor(player);
		float ceiling = getCeiling(player);
		float luck = state.getLuckValue(floor, ceiling);
		return luck * CustomConfig.LUCK_MAX_BONUS;
	}

	/**
	 * Roll with luck for enchantment. Applies luck bonus, then records
	 * the outcome to update momentum.
	 *
	 * @param player     the player
	 * @param baseChance base success percentage (0-100)
	 * @return true if the roll succeeds
	 */
	public static boolean rollEnchant(Player player, float baseChance) {
		return rollWithLuck(player, baseChance, ENCHANT_FAIL_INCREMENT);
	}

	/**
	 * Roll with luck for manastone socketing.
	 */
	public static boolean rollSocket(Player player, float baseChance) {
		return rollWithLuck(player, baseChance, SOCKET_FAIL_INCREMENT);
	}

	/**
	 * Roll with luck for crafting critical proc.
	 */
	public static boolean rollCraft(Player player, float baseChance) {
		return rollWithLuck(player, baseChance, CRAFT_FAIL_INCREMENT);
	}

	/**
	 * Get a passive drop rate multiplier (no luck consumption).
	 * Based on maxFP from gear — always active.
	 *
	 * @return multiplier (1.0 = no bonus, 1.05 = +5% drops)
	 */
	public static float getDropMultiplier(Player player) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED)
			return 1.0f;
		int maxFp = player.getLifeStats().getMaxFp();
		int baseFp = CustomConfig.BASE_FLYTIME;
		if (maxFp <= baseFp)
			return 1.0f;
		// Every 100 FP above base = +1% drop rate bonus, capped
		float bonusPercent = (maxFp - baseFp) / 100.0f;
		return 1.0f + Math.min(bonusPercent, CustomConfig.LUCK_MAX_DROP_BONUS) / 100.0f;
	}

	/**
	 * Apply an FP potion as a luck boost.
	 * Called from FP heal effects to also boost luck.
	 */
	public static void onFpPotionUsed(Player player) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED)
			return;
		player.getLuckState().boost(POTION_BOOST);
	}

	// ==================== Combat Passives ====================

	/**
	 * Combat damage roll with luck skew (传奇-style lucky hit).
	 * Replaces plain Rnd.get(min, max) for player weapon swings.
	 *
	 * At raw luck <= 50%: plain uniform roll (no penalty).
	 * At raw luck > 50%: power-law bias toward max damage.
	 *   skew = rawLuck - 0.5  →  roll^(1 - skew) crowds samples toward 1.0.
	 *   E.g. rawLuck 0.95 → average hit at ~64% of range instead of 50%.
	 *
	 * No pity/momentum side-effect — reads raw value to avoid draining
	 * the player's enchant pity counter on every combat swing.
	 *
	 * @param player    the attacking player
	 * @param minDamage weapon minimum damage
	 * @param maxDamage weapon maximum damage
	 * @return damage roll biased toward maxDamage at high luck
	 */
	public static int rollDamageSkew(Player player, int minDamage, int maxDamage) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED || minDamage >= maxDamage)
			return Rnd.get(minDamage, maxDamage);
		float rawLuck = player.getLuckState().getRawLuckValue(); // read without drift
		float skew = Math.max(0f, rawLuck - 0.5f); // 0.0 to 0.5, only above neutral
		if (skew == 0f)
			return Rnd.get(minDamage, maxDamage);
		// Power-law: roll^(1-skew) biases the uniform [0,1] sample toward 1.0
		float roll = (float) Math.pow(Rnd.nextFloat(), 1.0 - skew);
		int range = maxDamage - minDamage;
		return minDamage + Math.min(range, (int) (roll * (range + 1)));
	}

	/**
	 * Passive physical/magical crit rate bonus from luck momentum.
	 * Returns a bonus on the 0-1000 scale used by StatFunctions probability rolls.
	 * Max at full raw luck: +25 (= +2.5% absolute crit chance).
	 * No reset on use — reads raw luck to preserve enchant pity counter.
	 */
	public static int getPassiveCritBonus(Player player) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED) return 0;
		return (int) (player.getLuckState().getRawLuckValue() * 25);
	}

	/**
	 * Passive evasion/parry/block bonus from luck momentum.
	 * Returns a bonus on the 0-1000 scale used by StatFunctions probability rolls.
	 * Max at full raw luck: +20 (= +2.0% absolute dodge/parry/block chance).
	 * Applied to the DEFENDER when the defender is a player.
	 */
	public static int getPassiveEvasionBonus(Player player) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED) return 0;
		return (int) (player.getLuckState().getRawLuckValue() * 20);
	}

	/**
	 * Lucky landing: chance to halve fall damage when luck is above 50%.
	 * Chance scales linearly from 0% at rawLuck=50% to 25% at rawLuck=100%.
	 * Gives players an occasional "whew, lucky!" moment from dangerous falls.
	 *
	 * @param player the falling player
	 * @param damage the calculated fall damage before any reduction
	 * @return potentially halved damage
	 */
	public static int reduceFallDamage(Player player, int damage) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED || damage <= 0) return damage;
		float rawLuck = player.getLuckState().getRawLuckValue();
		// Only activates above neutral luck (0.5); max 25% trigger chance
		float chance = Math.max(0f, rawLuck - 0.5f) * 0.5f;
		if (chance > 0f && Rnd.nextFloat() < chance)
			return damage / 2;
		return damage;
	}

	// ==================== Internal ====================

	/**
	 * Core roll: apply luck bonus to base chance, roll, and update momentum.
	 * On success: luck resets to floor (spent your luck).
	 * On failure: luck increases (building toward pity).
	 */
	private static boolean rollWithLuck(Player player, float baseChance, float failIncrement) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED)
			return Rnd.chance() < baseChance;

		PlayerLuckState state = player.getLuckState();
		float floor = getFloor(player);
		float ceiling = getCeiling(player);
		float luck = state.getLuckValue(floor, ceiling);
		float bonus = luck * CustomConfig.LUCK_MAX_BONUS;
		float finalChance = baseChance + bonus;

		// Forced success after extended pity (5+ consecutive failures at ceiling)
		if (state.getConsecutiveFailures() >= 5 && luck >= ceiling * 0.95f)
			finalChance = 100f;

		boolean success = Rnd.chance() < finalChance;

		if (success)
			state.onSuccess(floor);
		else
			state.onFailure(failIncrement);

		return success;
	}

	/**
	 * Luck floor: minimum luck after a success reset.
	 * With pet summoned: 35%. Without: 20%.
	 */
	private static float getFloor(Player player) {
		boolean hasPet = player.getPet() != null;
		return hasPet ? FLOOR_WITH_PET : FLOOR_NO_PET;
	}

	/**
	 * Luck ceiling: maximum luck from pity buildup.
	 * Determined by maxFP from gear (wings, accessories).
	 * Base FP 600 → ceiling 80%. Best gear 1200+ → ceiling 90%.
	 */
	private static float getCeiling(Player player) {
		int maxFp = player.getLifeStats().getMaxFp();
		int baseFp = CustomConfig.BASE_FLYTIME;
		float ceiling = 0.80f + (maxFp - baseFp) / 6000.0f;
		return Math.min(0.95f, Math.max(0.80f, ceiling));
	}
}
