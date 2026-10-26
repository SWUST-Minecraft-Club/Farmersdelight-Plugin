package com.huidu.farmersdelight.worldgen;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans and commits additions within one loaded chunk; never runs Minecraft terrain/feature generators. */
public final class RetroGenerationEngine {
    public static final int MARGIN = 3;
    private static final Set<Material> NATURAL = Set.of(Material.AIR, Material.CAVE_AIR,
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.MYCELIUM,
            Material.ROOTED_DIRT, Material.MUD, Material.SAND, Material.RED_SAND, Material.GRAVEL,
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.DEEPSLATE,
            Material.TUFF, Material.CALCITE, Material.WATER, Material.SNOW, Material.SNOW_BLOCK,
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.VINE, Material.GLOW_LICHEN,
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET,
            Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
            Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER,
            Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.CLAY, Material.SUGAR_CANE);

    private RetroGenerationEngine() { }

    public interface Terrain {
        int chunkX();
        int chunkZ();
        int minHeight();
        int maxHeight();
        long worldSeed();
        String worldName();
        int spawnX();
        int spawnZ();
        int surfaceY(int localX, int localZ);
        default RetroGenerationPlacements.Result placements(RetroGenerationSettings settings, RetroGenerationRule rule) {
            return RetroGenerationPlacements.sample(this, settings, rule);
        }
        Material type(int localX, int y, int localZ);
        /** Neighbor guards may read only already loaded chunks owned by the current region. */
        default boolean readable(int localX, int localZ) { return localX >= 0 && localX <= 15 && localZ >= 0 && localZ <= 15; }
        boolean custom(int localX, int y, int localZ);
        /** Only plants successfully placed by this pass, still matching the exact placed state. */
        default boolean freshPlant(int localX, int y, int localZ) { return false; }
        String biome(int localX, int y, int localZ);
        boolean skyExposed(int localX, int y, int localZ);
        boolean unclaimed(int localX, int y, int localZ);
        boolean hasBlockEntities();
        boolean processed();
        void markProcessed();
        boolean active();
        /** Must re-check all guards after delivering any cancellable plugin event. */
        boolean place(Candidate candidate);
    }

    public enum Rejection { OCCUPIED, SOIL, CUSTOM_BLOCK, BUILDING, PROTECTED, EXCLUDED, BIOME, CHANCE, HEIGHT }
    public record Candidate(RetroGenerationRule rule, int x, int y, int z) { }
    public record Plan(List<Candidate> candidates, Map<Rejection, Integer> rejected, String reason) { }

    public static Plan plan(Terrain terrain, RetroGenerationSettings settings, List<RetroGenerationRule> rules) {
        if (terrain.processed()) return empty("processed");
        if (!terrain.active()) return empty("unavailable");
        if (!settings.worlds().contains(terrain.worldName())) return empty("world");
        if (terrain.hasBlockEntities()) return empty("block_entities");
        List<Candidate> candidates = new ArrayList<>();
        Map<Rejection, Integer> rejected = new EnumMap<>(Rejection.class);
        Set<RetroGenerationPlacements.Position> positions = new HashSet<>();
        for (RetroGenerationRule rule : rules) {
            if (!rule.worlds().isEmpty() && !rule.worlds().contains(terrain.worldName())) continue;
            var placement = terrain.placements(settings, rule);
            if (placement.chanceMissed()) {
                rejected.merge(Rejection.CHANCE, 1, Integer::sum);
                continue;
            }
            for (var position : placement.positions()) {
                if (!terrain.active()) return empty("unavailable");
                Candidate candidate = new Candidate(rule, position.x(), position.y(), position.z());
                Rejection rejection = rejection(terrain, settings, candidate);
                if (rejection != null) rejected.merge(rejection, 1, Integer::sum);
                else if (positions.add(position)) candidates.add(candidate);
            }
        }
        return new Plan(List.copyOf(candidates), Map.copyOf(rejected), "ready");
    }

    public static int commit(Terrain terrain, RetroGenerationSettings settings, Plan plan) {
        if (!"ready".equals(plan.reason()) || terrain.processed() || !terrain.active()) return 0;
        // The marker and block additions are saved with the chunk. Reserve before the first write so an
        // interrupted pass cannot keep adding plants on later loads; a partial pass stays partial.
        terrain.markProcessed();
        int placed = 0;
        for (Candidate candidate : plan.candidates()) {
            if (!terrain.active()) break;
            if (rejection(terrain, settings, candidate) == null && terrain.place(candidate)) placed++;
        }
        return placed;
    }

    public static Rejection rejection(Terrain terrain, RetroGenerationSettings settings, Candidate candidate) {
        int x = candidate.x(), y = candidate.y(), z = candidate.z();
        if (x < 0 || x > 15 || z < 0 || z > 15
                || y < terrain.minHeight() + 3 || y >= terrain.maxHeight() - 4) return Rejection.HEIGHT;
        if (settings.excludes(terrain.worldName(), (terrain.chunkX() << 4) + x, (terrain.chunkZ() << 4) + z,
                terrain.spawnX(), terrain.spawnZ())) return Rejection.EXCLUDED;
        if (terrain.type(x, y, z) != Material.AIR) return Rejection.OCCUPIED;
        if (terrain.custom(x, y, z) || terrain.custom(x, y - 1, z)) return Rejection.CUSTOM_BLOCK;
        if (!candidate.rule().soils().contains(terrain.type(x, y - 1, z))
                || !safeSoil(terrain.type(x, y - 1, z))) return Rejection.SOIL;
        if (!candidate.rule().biomes().contains(terrain.biome(x, y, z))) return Rejection.BIOME;
        if (!terrain.skyExposed(x, y, z)) return Rejection.BUILDING;
        if (!terrain.unclaimed(x, y, z) || !terrain.unclaimed(x, y - 1, z)) return Rejection.PROTECTED;
        for (int dx = -MARGIN; dx <= MARGIN; dx++) {
            for (int dz = -MARGIN; dz <= MARGIN; dz++) {
                if (!terrain.readable(x + dx, z + dz)) return Rejection.HEIGHT;
                for (int dy = -2; dy <= 3; dy++) {
                    if (terrain.freshPlant(x + dx, y + dy, z + dz)) continue;
                    Material type = terrain.type(x + dx, y + dy, z + dz);
                    if (!natural(type) || terrain.custom(x + dx, y + dy, z + dz)) return Rejection.BUILDING;
                }
            }
        }
        return null;
    }

    static boolean natural(Material type) {
        return NATURAL.contains(type) || type.name().endsWith("_LOG") || type.name().endsWith("_LEAVES")
                || type.name().endsWith("_ORE") || type.name().endsWith("_TERRACOTTA");
    }

    private static boolean safeSoil(Material type) {
        return switch (type) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, PODZOL, MYCELIUM, ROOTED_DIRT, MUD, SAND, RED_SAND -> true;
            default -> false;
        };
    }

    private static Plan empty(String reason) { return new Plan(List.of(), Map.of(), reason); }
}
