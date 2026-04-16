package com.aionemu.gameserver.controllers.movement;

import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.geometry.Point3D;
import com.aionemu.gameserver.world.geo.GeoService;

/**
 * A* pathfinding on a 2m grid using GeoService walkability primitives.
 *
 * Grid cell = 2m x 2m, aligned with Terrain.HEIGHTMAP_UNIT_SIZE.
 * 8-directional movement (4 cardinal + 4 diagonal).
 * Diagonal moves require both adjacent cardinal cells to be walkable
 * (prevents corner-cutting through walls).
 *
 * Cost function: cardinal=1.0, diagonal=1.414, + 0.5 * |deltaZ| height penalty.
 * Heuristic: Octile distance (admissible and consistent for 8-dir).
 *
 * Thread safety: stateless utility class. All working memory is stack-local.
 * GeoService calls are thread-safe.
 */
public class NpcPathFinder {

    private static final float GRID_SIZE = 2.0f;
    private static final float DIAGONAL_COST = 1.414f;
    private static final float CARDINAL_COST = 1.0f;
    private static final float HEIGHT_PENALTY = 0.5f;
    private static final float Z_SEARCH_UP = 10f;
    private static final float Z_SEARCH_DOWN = 10f;
    private static final float MAX_STEP_HEIGHT = 2.5f;
    // Open-set node array size. Each expansion can add up to 8 neighbors;
    // budget = maxNodes * 8 is a safe upper bound.
    // 200 nodes * 8 = 1600; give 25% margin → 2048.
    private static final int MAX_OPEN_SET = 4096;
    // Visited-set grid: 128×128 cells = 256m × 256m radius.
    // Previous 64×64 = 128m was too small for terrain detours.
    private static final int VISITED_GRID = 128;
    private static final int VISITED_HALF = VISITED_GRID / 2;

    // 8 neighbors: dx, dy
    private static final int[][] DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},  // cardinal
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal
    };

    /**
     * Compute A* path from start to goal on a 2m grid.
     *
     * @param worldId     world map ID
     * @param instanceId  instance ID
     * @param startX      NPC world X
     * @param startY      NPC world Y
     * @param startZ      NPC world Z
     * @param goalX       target world X
     * @param goalY       target world Y
     * @param goalZ       target world Z
     * @param maxNodes    max nodes to expand (budget)
     * @param out         pre-allocated array to write waypoints into
     * @return number of waypoints, or 0 if no path found
     */
    public static int findPath(int worldId, int instanceId,
                                float startX, float startY, float startZ,
                                float goalX, float goalY, float goalZ,
                                int maxNodes, Point3D[] out) {

        int sx = toGrid(startX), sy = toGrid(startY);
        int gx = toGrid(goalX), gy = toGrid(goalY);

        // Trivial: already at goal cell
        if (sx == gx && sy == gy) {
            out[0].setX(goalX);
            out[0].setY(goalY);
            out[0].setZ(goalZ);
            return 1;
        }

        // A* working memory (heap; each call allocates ~capacity * 28 bytes)
        // capacity = maxNodes * 8 neighbors per node, capped at MAX_OPEN_SET
        int capacity = Math.min(maxNodes * 8, MAX_OPEN_SET);
        int[] nodeGridX = new int[capacity];
        int[] nodeGridY = new int[capacity];
        float[] nodeZ = new float[capacity];
        float[] nodeG = new float[capacity];
        float[] nodeF = new float[capacity];
        int[] nodeParent = new int[capacity];
        boolean[] nodeClosed = new boolean[capacity];
        int nodeCount = 0;

        // Closed set: bit-indexed local grid (128×128 = 256m × 256m)
        int gridOriginX = sx - VISITED_HALF, gridOriginY = sy - VISITED_HALF;
        boolean[][] visited = new boolean[VISITED_GRID][VISITED_GRID];

        // Add start node
        nodeGridX[0] = sx;
        nodeGridY[0] = sy;
        nodeZ[0] = startZ;
        nodeG[0] = 0;
        nodeF[0] = heuristic(sx, sy, gx, gy);
        nodeParent[0] = -1;
        nodeCount = 1;
        markVisited(visited, sx, sy, gridOriginX, gridOriginY);

        int expansions = 0;
        int goalNode = -1;

        while (expansions < maxNodes) {
            // Find open node with lowest f
            int bestIdx = -1;
            float bestF = Float.MAX_VALUE;
            for (int i = 0; i < nodeCount; i++) {
                if (!nodeClosed[i] && nodeF[i] < bestF) {
                    bestF = nodeF[i];
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break; // no open nodes left

            // Check if we reached the goal
            if (nodeGridX[bestIdx] == gx && nodeGridY[bestIdx] == gy) {
                goalNode = bestIdx;
                break;
            }

            nodeClosed[bestIdx] = true;
            expansions++;

            int cx = nodeGridX[bestIdx], cy = nodeGridY[bestIdx];
            float cz = nodeZ[bestIdx], cg = nodeG[bestIdx];

            // Expand 8 neighbors
            for (int d = 0; d < 8; d++) {
                int nx = cx + DIRS[d][0];
                int ny = cy + DIRS[d][1];

                // Skip if already visited
                if (isVisited(visited, nx, ny, gridOriginX, gridOriginY)) continue;

                // Out of local grid bounds
                int lx = nx - gridOriginX, ly = ny - gridOriginY;
                if (lx < 0 || lx >= VISITED_GRID || ly < 0 || ly >= VISITED_GRID) continue;

                // Check walkability: query ground Z at neighbor cell center
                float nwx = toWorld(nx), nwy = toWorld(ny);
                float nz = GeoService.getInstance().getWalkableZ(
                    worldId, nwx, nwy, cz + Z_SEARCH_UP, cz - Z_SEARCH_DOWN, instanceId);
                if (Float.isNaN(nz)) continue; // no walkable ground

                // Height difference check (prevents cliff jumps)
                float zDiff = Math.abs(nz - cz);
                if (zDiff > MAX_STEP_HEIGHT) continue;

                // Stability check: verify the surface is actually walkable ground,
                // not a steep pillar/wall surface. Query regular getZ (without slope
                // ignore) and check it matches walkableZ within 1m tolerance.
                // If they differ significantly, the "walkable" point is on a curved
                // surface that the NPC can't actually stand on.
                float regularZ = GeoService.getInstance().getZ(
                    worldId, nwx, nwy, nz + 1, nz - 1, instanceId);
                if (Float.isNaN(regularZ) || Math.abs(regularZ - nz) > 1f) continue;

                // Diagonal: both adjacent cardinal cells must be walkable
                boolean isDiag = d >= 4;
                if (isDiag) {
                    int adjX1 = cx + DIRS[d][0], adjY1 = cy;
                    int adjX2 = cx, adjY2 = cy + DIRS[d][1];
                    float z1 = GeoService.getInstance().getWalkableZ(
                        worldId, toWorld(adjX1), toWorld(adjY1), cz + Z_SEARCH_UP, cz - Z_SEARCH_DOWN, instanceId);
                    float z2 = GeoService.getInstance().getWalkableZ(
                        worldId, toWorld(adjX2), toWorld(adjY2), cz + Z_SEARCH_UP, cz - Z_SEARCH_DOWN, instanceId);
                    if (Float.isNaN(z1) || Float.isNaN(z2)) continue;
                }

                // Wall check: ray from current center to neighbor center
                float cwx = toWorld(cx), cwy = toWorld(cy);
                Vector3f col = GeoService.getInstance().getClosestCollision(
                    cwx, cwy, cz + 0.5f, nwx, nwy, nz + 0.5f,
                    true, worldId, instanceId);
                float cdx = col.getX() - cwx, cdy = col.getY() - cwy;
                float colDistSq = cdx * cdx + cdy * cdy;
                float stepDistSq = (nwx - cwx) * (nwx - cwx) + (nwy - cwy) * (nwy - cwy);
                if (colDistSq + 0.5f < stepDistSq) continue; // wall blocks path

                // Compute cost
                float baseCost = isDiag ? DIAGONAL_COST : CARDINAL_COST;
                float ng = cg + baseCost + HEIGHT_PENALTY * zDiff;
                float nf = ng + heuristic(nx, ny, gx, gy);

                // Add node
                if (nodeCount >= capacity) break; // safety limit
                int ni = nodeCount++;
                nodeGridX[ni] = nx;
                nodeGridY[ni] = ny;
                nodeZ[ni] = nz;
                nodeG[ni] = ng;
                nodeF[ni] = nf;
                nodeParent[ni] = bestIdx;
                markVisited(visited, nx, ny, gridOriginX, gridOriginY);
            }
        }

        // No path found
        if (goalNode < 0) return 0;

        // Reconstruct path (backtrack from goal to start)
        int pathLen = 0;
        int node = goalNode;
        while (node >= 0 && pathLen < NpcPathState.MAX_WAYPOINTS) {
            pathLen++;
            node = nodeParent[node];
        }

        // Write waypoints in forward order
        int writeIdx = pathLen - 1;
        node = goalNode;
        while (node >= 0 && writeIdx >= 0) {
            out[writeIdx].setX(toWorld(nodeGridX[node]));
            out[writeIdx].setY(toWorld(nodeGridY[node]));
            out[writeIdx].setZ(nodeZ[node]);
            node = nodeParent[node];
            writeIdx--;
        }

        // Replace last waypoint with exact goal position
        if (pathLen > 0) {
            out[pathLen - 1].setX(goalX);
            out[pathLen - 1].setY(goalY);
            out[pathLen - 1].setZ(goalZ);
        }

        // Smooth: remove intermediate waypoints with clear line-of-sight
        return smoothPath(worldId, instanceId, out, pathLen);
    }

    // ==================== Grid conversion ====================

    private static int toGrid(float worldCoord) {
        return (int) Math.floor(worldCoord / GRID_SIZE);
    }

    private static float toWorld(int gridCoord) {
        return gridCoord * GRID_SIZE + GRID_SIZE * 0.5f;
    }

    // ==================== Heuristic ====================

    /** Octile distance: exact for 8-directional grid, admissible + consistent */
    private static float heuristic(int ax, int ay, int bx, int by) {
        int dx = Math.abs(bx - ax), dy = Math.abs(by - ay);
        return CARDINAL_COST * Math.max(dx, dy) + (DIAGONAL_COST - CARDINAL_COST) * Math.min(dx, dy);
    }

    // ==================== Visited set ====================

    private static void markVisited(boolean[][] visited, int gx, int gy, int ox, int oy) {
        int lx = gx - ox, ly = gy - oy;
        if (lx >= 0 && lx < VISITED_GRID && ly >= 0 && ly < VISITED_GRID) {
            visited[lx][ly] = true;
        }
    }

    private static boolean isVisited(boolean[][] visited, int gx, int gy, int ox, int oy) {
        int lx = gx - ox, ly = gy - oy;
        if (lx < 0 || lx >= VISITED_GRID || ly < 0 || ly >= VISITED_GRID) return false;
        return visited[lx][ly];
    }

    // ==================== Path smoothing ====================

    /** Remove intermediate waypoints that have direct line-of-sight */
    private static int smoothPath(int worldId, int instanceId, Point3D[] path, int len) {
        if (len <= 2) return len;
        Point3D[] smoothed = new Point3D[len];
        smoothed[0] = path[0];
        int smoothLen = 1;
        int current = 0;

        while (current < len - 1) {
            // Try to skip as far ahead as possible while maintaining line-of-sight
            int farthest = current + 1;
            for (int ahead = current + 2; ahead < len; ahead++) {
                Vector3f col = GeoService.getInstance().getClosestCollision(
                    path[current].getX(), path[current].getY(), path[current].getZ() + 0.5f,
                    path[ahead].getX(), path[ahead].getY(), path[ahead].getZ() + 0.5f,
                    true, worldId, instanceId);
                float dx = col.getX() - path[current].getX();
                float dy = col.getY() - path[current].getY();
                float colDistSq = dx * dx + dy * dy;
                float targetDx = path[ahead].getX() - path[current].getX();
                float targetDy = path[ahead].getY() - path[current].getY();
                float targetDistSq = targetDx * targetDx + targetDy * targetDy;
                if (colDistSq + 1f < targetDistSq) break; // wall blocks
                farthest = ahead;
            }
            current = farthest;
            if (smoothLen < len) {
                smoothed[smoothLen] = path[current];
                smoothLen++;
            }
        }

        // Copy smoothed back to path array
        for (int i = 0; i < smoothLen; i++) {
            path[i].setX(smoothed[i].getX());
            path[i].setY(smoothed[i].getY());
            path[i].setZ(smoothed[i].getZ());
        }
        return smoothLen;
    }
}
