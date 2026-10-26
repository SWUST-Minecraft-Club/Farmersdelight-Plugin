package com.huidu.farmersdelight.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable settings shared by the global dispatcher and the chunk's owning region. */
public record RetroGenerationSettings(boolean enabled, Set<String> worlds, int chunksPerTick,
                                      int attemptsPerPlant, int maxPlantsPerChunk, double densityMultiplier,
                                      long seed, int spawnExclusionRadius, List<ExcludedArea> excludedAreas,
                                      Set<String> extraProtectionPlugins) {
    public static RetroGenerationSettings load(ConfigurationSection config, String primaryWorld) {
        String root = "retro-generation.";
        if (config.get("retro-generation") != null && !config.isConfigurationSection("retro-generation")) {
            throw new IllegalArgumentException("retro-generation must be a section");
        }
        List<String> configuredWorlds = stringList(config, root + "worlds");
        Set<String> worlds = configuredWorlds.isEmpty() ? Set.of(primaryWorld) : Set.copyOf(configuredWorlds);
        Object rawDensity = config.get(root + "density-multiplier", 1.0);
        if (!(rawDensity instanceof Number number)) {
            throw new IllegalArgumentException(root + "density-multiplier must be a number");
        }
        double density = number.doubleValue();
        if (!Double.isFinite(density) || density <= 0 || density > 1000) {
            throw new IllegalArgumentException("retro-generation.density-multiplier must be in (0, 1000]");
        }
        List<ExcludedArea> excluded = new ArrayList<>();
        Object rawAreas = config.get(root + "excluded-areas", List.of());
        if (!(rawAreas instanceof List<?> areas)) throw new IllegalArgumentException(root + "excluded-areas must be a list");
        for (Object area : areas) {
            if (!(area instanceof Map<?, ?> entry)) throw new IllegalArgumentException("Each excluded area must be a map");
            if (!(entry.get("world") instanceof String world)) {
                throw new IllegalArgumentException("Each retro-generation excluded area needs a world");
            }
            excluded.add(new ExcludedArea(world, coordinate(entry, "min-x"), coordinate(entry, "max-x"),
                    coordinate(entry, "min-z"), coordinate(entry, "max-z")));
        }
        Object enabled = config.get(root + "enabled", false);
        if (!(enabled instanceof Boolean)) throw new IllegalArgumentException(root + "enabled must be true or false");
        Object seed = config.get(root + "seed", 0L);
        if (!(seed instanceof Byte || seed instanceof Short || seed instanceof Integer || seed instanceof Long)) {
            throw new IllegalArgumentException(root + "seed must be an integer");
        }
        return new RetroGenerationSettings((Boolean) enabled, worlds,
                bounded(config, root + "chunks-per-tick", 1, 1, 4),
                bounded(config, root + "attempts-per-plant", 32, 1, 64),
                bounded(config, root + "max-plants-per-chunk", 3, 1, 8), density,
                ((Number) seed).longValue(),
                bounded(config, root + "exclude-spawn-radius", 128, 0, 30_000_000), List.copyOf(excluded),
                Set.copyOf(stringList(config, root + "extra-protection-plugins")));
    }

    private static int bounded(ConfigurationSection config, String path, int fallback, int min, int max) {
        Object raw = config.get(path, fallback);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        int value = number.intValue();
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static List<String> stringList(ConfigurationSection config, String path) {
        Object raw = config.get(path, List.of());
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException(path + " must be a list");
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(path + " needs nonempty names");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static int coordinate(Map<?, ?> entry, String key) {
        if (!(entry.get(key) instanceof Number value) || value.doubleValue() != value.intValue()) {
            throw new IllegalArgumentException("Excluded area needs an integer " + key);
        }
        return value.intValue();
    }

    public boolean excludes(String world, int x, int z, int spawnX, int spawnZ) {
        long dx = (long) x - spawnX;
        long dz = (long) z - spawnZ;
        if (spawnExclusionRadius > 0 && Math.abs(dx) <= spawnExclusionRadius
                && Math.abs(dz) <= spawnExclusionRadius) return true;
        return excludedAreas.stream().anyMatch(area -> area.contains(world, x, z));
    }

    public record ExcludedArea(String world, int minX, int maxX, int minZ, int maxZ) {
        public ExcludedArea {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Excluded area bounds are reversed");
        }

        boolean contains(String name, int x, int z) {
            return world.equals(name) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
