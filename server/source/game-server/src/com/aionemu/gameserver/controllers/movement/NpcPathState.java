package com.aionemu.gameserver.controllers.movement;

import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.model.geometry.Point3D;
import com.aionemu.gameserver.utils.PositionUtil;

/**
 * Per-NPC pathfinding state: cached A* waypoints, recompute timers,
 * and failure tracking. Created lazily when pathfinding is first needed.
 *
 * Thread safety: each NPC has its own instance; no sharing.
 */
public class NpcPathState {

    public static final int MAX_WAYPOINTS = 50;
    private static final float TARGET_MOVE_THRESHOLD_SQ = 9f; // 3m squared
    private static final long CANNOT_REACH_TIMEOUT_MS = 5000;
    private static final float WAYPOINT_REACH_DIST = 1.5f;
    // Give-up timeout is read from GeoDataConfig at call time (not cached here)

    // Path data (pre-allocated array to avoid GC)
    private final Point3D[] waypoints = new Point3D[MAX_WAYPOINTS];
    private int waypointCount;
    private int currentIndex;

    // Cache invalidation
    private float lastTargetX, lastTargetY, lastTargetZ;
    private long lastComputeTime;
    private long lastRecheckTime;
    private int recheckIntervalMs = 100;

    // Failure tracking
    private int consecutiveFailures;
    private boolean cannotReachTarget;
    private long cannotReachSetTime;
    /** Timestamp of the first cannotReach event in the current stuck sequence; 0 if not stuck */
    private long firstCannotReachTime;

    // Per-waypoint stuck detection
    private long waypointStartTime;
    private static final long WAYPOINT_TIMEOUT_MS = 3000; // 3s max per waypoint

    public NpcPathState() {
        for (int i = 0; i < MAX_WAYPOINTS; i++) {
            waypoints[i] = new Point3D(0, 0, 0);
        }
    }

    // ==================== Path access ====================

    /** @return true if a valid path with remaining waypoints exists */
    public boolean hasPath() {
        return waypointCount > 0 && currentIndex < waypointCount;
    }

    /** @return the current waypoint to move toward, or null if no path */
    public Point3D getCurrentWaypoint() {
        if (!hasPath()) return null;
        return waypoints[currentIndex];
    }

    /**
     * Check if owner reached current waypoint and advance.
     * @return true if advanced to next waypoint, false if path ended
     */
    public boolean checkAndAdvance(float ownerX, float ownerY, float ownerZ) {
        if (!hasPath()) return false;
        Point3D wp = waypoints[currentIndex];
        if (PositionUtil.isInRange(ownerX, ownerY, ownerZ, wp.getX(), wp.getY(), wp.getZ(), WAYPOINT_REACH_DIST)) {
            currentIndex++;
            waypointStartTime = System.currentTimeMillis();
            return currentIndex < waypointCount;
        }
        // Stuck at current waypoint too long — skip it or invalidate path
        if (waypointStartTime > 0 && (System.currentTimeMillis() - waypointStartTime) > WAYPOINT_TIMEOUT_MS) {
            if (currentIndex < waypointCount - 1) {
                currentIndex++; // skip unreachable waypoint
                waypointStartTime = System.currentTimeMillis();
            } else {
                clearPath(); // last waypoint unreachable, give up this path
                return false;
            }
        }
        return true;
    }

    // ==================== Path setting ====================

    /**
     * Store a computed path. Copies data into the pre-allocated array.
     * @param srcWaypoints source waypoint array from NpcPathFinder
     * @param count number of valid entries
     */
    public void setPath(Point3D[] srcWaypoints, int count,
                        float targetX, float targetY, float targetZ) {
        this.waypointCount = Math.min(count, MAX_WAYPOINTS);
        for (int i = 0; i < this.waypointCount; i++) {
            waypoints[i].setX(srcWaypoints[i].getX());
            waypoints[i].setY(srcWaypoints[i].getY());
            waypoints[i].setZ(srcWaypoints[i].getZ());
        }
        this.currentIndex = 0;
        this.waypointStartTime = System.currentTimeMillis();
        this.lastTargetX = targetX;
        this.lastTargetY = targetY;
        this.lastTargetZ = targetZ;
        this.lastComputeTime = System.currentTimeMillis();
    }

    /** Clear all path data */
    public void clearPath() {
        waypointCount = 0;
        currentIndex = 0;
    }

    // ==================== Recompute logic ====================

    /**
     * Check if path needs recomputation.
     * Returns true if: no path, target moved >3m, or recheck interval elapsed.
     */
    public boolean needsRecompute(float targetX, float targetY, float targetZ, long nowMs) {
        // Don't retry if flagged unreachable (auto-clears after 5s)
        if (cannotReachTarget) {
            if ((nowMs - cannotReachSetTime) < CANNOT_REACH_TIMEOUT_MS) {
                return false;
            }
            cannotReachTarget = false;
        }
        // No path at all
        if (waypointCount == 0) return true;
        // Throttle recheck
        if ((nowMs - lastRecheckTime) < recheckIntervalMs) return false;
        lastRecheckTime = nowMs;
        // Target moved significantly?
        float dx = targetX - lastTargetX;
        float dy = targetY - lastTargetY;
        return (dx * dx + dy * dy) > TARGET_MOVE_THRESHOLD_SQ;
    }

    /**
     * Find the NPC's current position on the existing path and skip
     * already-passed waypoints. Reuses the path prefix.
     */
    public void skipToClosestWaypoint(float npcX, float npcY, float npcZ) {
        if (!hasPath()) return;
        float bestDistSq = Float.MAX_VALUE;
        int bestIdx = currentIndex;
        for (int i = currentIndex; i < waypointCount; i++) {
            Point3D wp = waypoints[i];
            float dx = npcX - wp.getX();
            float dy = npcY - wp.getY();
            float distSq = dx * dx + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIdx = i;
            }
        }
        // Skip to closest waypoint, advance past it if very close
        currentIndex = bestIdx;
        if (bestDistSq < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST && currentIndex < waypointCount - 1) {
            currentIndex++;
        }
    }

    // ==================== Failure tracking ====================

    /**
     * Mark target as unreachable. Auto-clears after CANNOT_REACH_TIMEOUT_MS.
     * Starts the give-up countdown on first call in a stuck sequence.
     */
    public void setCannotReachTarget() {
        if (!cannotReachTarget && firstCannotReachTime == 0) {
            firstCannotReachTime = System.currentTimeMillis(); // start give-up countdown
        }
        cannotReachTarget = true;
        cannotReachSetTime = System.currentTimeMillis();
        clearPath();
    }

    /**
     * Returns true when pathfinding has been persistently failing long enough
     * that the NPC should give up and return home.
     * Timeout comes from GeoDataConfig.PATHFINDING_GIVEUP_MS (default 30s).
     * Returns false if giveup is disabled (configured to 0).
     */
    public boolean shouldGiveUp() {
        int timeoutMs = GeoDataConfig.PATHFINDING_GIVEUP_MS;
        if (timeoutMs <= 0) return false; // disabled
        return firstCannotReachTime > 0
            && (System.currentTimeMillis() - firstCannotReachTime) >= timeoutMs;
    }

    public boolean isCannotReachTarget() {
        if (cannotReachTarget && (System.currentTimeMillis() - cannotReachSetTime) >= CANNOT_REACH_TIMEOUT_MS) {
            cannotReachTarget = false;
        }
        return cannotReachTarget;
    }

    public int incrementFailures() {
        return ++consecutiveFailures;
    }

    public void resetFailures() {
        consecutiveFailures = 0;
        firstCannotReachTime = 0; // path found — reset give-up countdown
    }

    // ==================== Config ====================

    public void setRecheckInterval(int ms) {
        this.recheckIntervalMs = ms;
    }

    /** @return the pre-allocated waypoint buffer for NpcPathFinder to write into */
    public Point3D[] getWaypointBuffer() {
        return waypoints;
    }
}
