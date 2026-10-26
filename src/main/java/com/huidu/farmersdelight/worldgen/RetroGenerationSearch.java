package com.huidu.farmersdelight.worldgen;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntFunction;

/** Bounded, read-only index of plants found in already loaded CE sections. */
public final class RetroGenerationSearch {
    public static final int RADIUS = 16;
    public static final int RESULT_LIMIT = 10;
    private static final Set<String> IDS = Set.of("farmersdelight:wild_cabbages", "farmersdelight:wild_onions",
            "farmersdelight:wild_tomatoes", "farmersdelight:wild_carrots", "farmersdelight:wild_potatoes",
            "farmersdelight:wild_beetroots", "farmersdelight:sandy_shrub", "farmersdelight:wild_rice");
    private final int originX;
    private final int originZ;
    private final Comparator<Position> nearest;
    private final PriorityQueue<Position> results;
    private long count;

    public RetroGenerationSearch(int originX, int originZ) {
        this.originX = originX;
        this.originZ = originZ;
        nearest = Comparator.comparingLong(this::distanceSquared).thenComparingInt(Position::x)
                .thenComparingInt(Position::y).thenComparingInt(Position::z).thenComparing(Position::id);
        results = new PriorityQueue<>(RESULT_LIMIT, nearest.reversed());
    }

    public static boolean isWildPlant(String id) { return id != null && IDS.contains(id); }

    public boolean includesChunk(int chunkX, int chunkZ) {
        return Math.abs((long) chunkX - (originX >> 4)) <= RADIUS
                && Math.abs((long) chunkZ - (originZ >> 4)) <= RADIUS;
    }

    /** Section entries are indexed by (localY << 8) | (localZ << 4) | localX. */
    public void scanSection(int chunkX, int chunkZ, int sectionY, IntFunction<String> idAt) {
        if (!includesChunk(chunkX, chunkZ)) return;
        for (int index = 0; index < 4096; index++) {
            String id = idAt.apply(index);
            if (!isWildPlant(id)) continue;
            Position position = new Position(id, (chunkX << 4) + (index & 15),
                    (sectionY << 4) + (index >> 8), (chunkZ << 4) + ((index >> 4) & 15));
            count++;
            if (results.size() < RESULT_LIMIT) results.add(position);
            else if (nearest.compare(position, results.peek()) < 0) {
                results.poll();
                results.add(position);
            }
        }
    }

    public long distanceSquared(Position position) {
        long dx = (long) position.x() - originX, dz = (long) position.z() - originZ;
        return dx * dx + dz * dz;
    }

    public long count() { return count; }
    public List<Position> nearest() { return results.stream().sorted(nearest).toList(); }
    public record Position(String id, int x, int y, int z) { }
}
