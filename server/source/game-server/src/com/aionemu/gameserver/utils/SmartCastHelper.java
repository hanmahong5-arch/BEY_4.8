package com.aionemu.gameserver.utils;

import java.util.Comparator;
import java.util.stream.Stream;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;

/**
 * Smart-cast auto-targeting helper.
 *
 * Resolves a target for offensive single-target skills when the player activates
 * them without selecting a target first.  Uses a two-pass search:
 *
 *   Pass 1 — Forward cone (SMART_CAST_CONE_ANGLE, default 120°):
 *     Mimics "skill fires in facing direction" — enemies you are looking at get hit
 *     naturally.  Direction is biased toward the current target if it is alive and
 *     hostile, giving fluid follow-up in sustained combat.
 *
 *   Pass 2 — Full 360° fallback (when SMART_CAST_FALLBACK_360 = true):
 *     If the cone yields nothing, finds the nearest enemy anywhere within range.
 *     Virtually eliminates "cannot find target" errors when enemies are nearby but
 *     not in the player's exact facing direction.
 *
 * This design produces an "action-RPG" feel without changing skill mechanics:
 *   - Selected target always takes priority (not handled here — caller check first)
 *   - Facing direction preferred when no selection exists
 *   - Nearest fallback ensures combat stays fluid
 */
public class SmartCastHelper {

    /**
     * Finds a valid enemy target for auto-cast using the two-pass strategy above.
     *
     * @param effector  the casting creature (typically a Player)
     * @param maxRange  maximum targeting range in game units; 0 or negative → 25 m
     * @return nearest valid enemy, or {@code null} if none found within range
     */
    public static Creature findNearestEnemyInCone(Creature effector, int maxRange) {
        int effectiveRange = maxRange > 0 ? maxRange : 25;

        // Pass 1: forward-facing cone — directional targeting
        float facingAngle = resolveFacingAngle(effector);
        float halfAngle   = CustomConfig.SMART_CAST_CONE_ANGLE / 2f;

        Creature inCone = streamEnemiesInRange(effector, effectiveRange)
            .filter(c -> isInCone(effector, c, facingAngle, halfAngle))
            .min(Comparator.comparingDouble(c -> PositionUtil.getDistance(effector, c)))
            .orElse(null);

        if (inCone != null)
            return inCone;

        // Pass 2: 360° fallback — nearest enemy anywhere in range
        if (CustomConfig.SMART_CAST_FALLBACK_360) {
            return streamEnemiesInRange(effector, effectiveRange)
                .min(Comparator.comparingDouble(c -> PositionUtil.getDistance(effector, c)))
                .orElse(null);
        }

        return null;
    }

    /**
     * Streams all living enemies of {@code effector} within {@code range} game units.
     * The stream may be consumed only once; callers must not call it in parallel.
     */
    private static Stream<Creature> streamEnemiesInRange(Creature effector, int range) {
        return effector.getKnownList().stream()
            .filter(ref -> ref.get() instanceof Creature)
            .map(ref -> (Creature) ref.get())
            .filter(c -> !c.isDead())
            .filter(c -> effector.isEnemy(c))
            .filter(c -> PositionUtil.isInRange(effector, c, range));
    }

    /**
     * Determines the "forward" direction angle (degrees) for the cone search.
     *
     * Priority:
     *   1. If the effector currently has an alive, hostile target → angle toward that
     *      target.  This gives natural feel: follow-up skills auto-find the same enemy
     *      even if the player briefly de-selects.
     *   2. Otherwise → the creature's heading (body/camera facing direction).
     */
    private static float resolveFacingAngle(Creature effector) {
        VisibleObject current = effector.getTarget();
        if (current instanceof Creature ct && !ct.isDead() && effector.isEnemy(ct))
            return PositionUtil.calculateAngleFrom(effector, ct);
        return PositionUtil.convertHeadingToAngle(effector.getHeading());
    }

    /**
     * Returns {@code true} if {@code candidate} lies within the cone defined by
     * {@code facingAngle} ± {@code halfAngle} (both in degrees, 0–360, wrap-safe).
     */
    private static boolean isInCone(Creature effector, Creature candidate,
                                    float facingAngle, float halfAngle) {
        float toTarget = PositionUtil.calculateAngleFrom(effector, candidate);
        float delta    = Math.abs(toTarget - facingAngle) % 360f;
        if (delta > 180f)
            delta = 360f - delta;
        return delta <= halfAngle;
    }
}
