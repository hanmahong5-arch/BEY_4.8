package com.aionemu.gameserver.model.gameobjects.player;

/**
 * Tracks the player's luck momentum state for the gambling system.
 *
 * Luck is a MOMENTUM value (0.0-1.0) that rises on failure and resets on success.
 * It is NOT a consumable resource — it reflects the player's "flow state" with RNG.
 *
 * Sources of luck:
 * - Consecutive failures push luck UP (+0.08 per enchant fail)
 * - Success RESETS luck to floor
 * - FP potions give instant +0.20 boost
 * - Pet proximity raises the floor from 0.20 to 0.35
 * - Natural drift toward 0.50 baseline (±0.01 per 6s)
 * - MaxFP from gear determines the ceiling (better wings = higher cap)
 *
 * The luck value maps linearly to a success rate bonus:
 *   bonus% = luckValue * MAX_LUCK_BONUS (default 15)
 */
public class PlayerLuckState {

	private float luckValue = 0.50f;
	private int consecutiveFailures = 0;
	private long lastUpdateTime = System.currentTimeMillis();

	// Pity acceleration threshold: after this many consecutive failures,
	// an extra +15% luck spike is applied once
	private static final int PITY_ACCELERATION_THRESHOLD = 5;
	private boolean pityAccelerated = false;

	/**
	 * Get current luck value (0.0 to 1.0).
	 * Applies natural drift toward 0.50 before returning.
	 */
	public float getLuckValue(float floor, float ceiling) {
		applyNaturalDrift();
		return Math.max(floor, Math.min(ceiling, luckValue));
	}

	/**
	 * Record a gambling failure — luck rises.
	 *
	 * @param increment luck increase (e.g., 0.08 for enchant, 0.05 for manastone)
	 */
	public void onFailure(float increment) {
		consecutiveFailures++;
		luckValue += increment;

		// Pity acceleration: after N consecutive failures, give an extra spike
		if (consecutiveFailures >= PITY_ACCELERATION_THRESHOLD && !pityAccelerated) {
			luckValue += 0.15f;
			pityAccelerated = true;
		}

		// Soft cap at 1.0 (hard cap applied when reading via floor/ceiling)
		if (luckValue > 1.0f)
			luckValue = 1.0f;

		lastUpdateTime = System.currentTimeMillis();
	}

	/**
	 * Record a gambling success — luck resets to floor.
	 *
	 * @param floor the luck floor (pet-dependent)
	 */
	public void onSuccess(float floor) {
		luckValue = floor;
		consecutiveFailures = 0;
		pityAccelerated = false;
		lastUpdateTime = System.currentTimeMillis();
	}

	/**
	 * Apply an instant luck boost (e.g., from FP potion).
	 *
	 * @param amount boost amount (e.g., 0.20)
	 */
	public void boost(float amount) {
		luckValue += amount;
		if (luckValue > 1.0f)
			luckValue = 1.0f;
		lastUpdateTime = System.currentTimeMillis();
	}

	/**
	 * Force a specific luck level (e.g., on login initialization).
	 */
	public void setLuckValue(float value) {
		this.luckValue = Math.max(0, Math.min(1.0f, value));
	}

	/**
	 * Get the raw luck value without applying drift — used for DB persistence only.
	 * Do NOT use this for game logic (use getLuckValue with floor/ceiling instead).
	 */
	public float getRawLuckValue() {
		return luckValue;
	}

	public int getConsecutiveFailures() {
		return consecutiveFailures;
	}

	/**
	 * Natural drift: luck moves toward 0.50 baseline over time.
	 * Rate: ~1% per 6 seconds (same rhythm as FP natural regen).
	 */
	private void applyNaturalDrift() {
		long now = System.currentTimeMillis();
		long elapsed = now - lastUpdateTime;
		if (elapsed < 6000)
			return;

		// How many 6-second ticks have passed
		int ticks = (int) (elapsed / 6000);
		float driftTarget = 0.50f;
		for (int i = 0; i < ticks; i++) {
			luckValue += (driftTarget - luckValue) * 0.02f;
		}
		lastUpdateTime = now;
	}
}
