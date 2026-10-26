package com.huidu.farmersdelight.worldgen;

import org.bukkit.Material;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only the bundled, single-block, terrestrial wild plants may be added to an existing map. */
public record RetroGenerationRule(String id, Set<String> biomes, Set<String> worlds,
                                  Set<Material> soils, int rarity, Patch patch) {
    public RetroGenerationRule(String id, Set<String> biomes, Set<String> worlds, Set<Material> soils, int rarity) {
        this(id, biomes, worlds, soils, rarity, new Patch(64, 6, 3, true));
    }

    /** The bundled native patches use symmetric triangular offsets, not uniform independent columns. */
    public record Patch(int tries, int horizontalSpread, int verticalSpread, boolean flat) {
        public Patch {
            if (tries < 1 || tries > 256 || horizontalSpread < 0 || horizontalSpread > 16
                    || verticalSpread < 0 || verticalSpread > 16) throw new IllegalArgumentException("Unsupported plant patch bounds");
        }
    }
    private static final Set<String> PLANTS = Set.of("wild_cabbages", "wild_onions", "wild_tomatoes",
            "wild_carrots", "wild_potatoes", "wild_beetroots", "sandy_shrub");

    public static List<RetroGenerationRule> load(Path configuration, String minecraftVersion) throws IOException {
        List<RetroGenerationRule> result = new ArrayList<>();
        for (String name : List.of("wild_plants.yml", "wild_roots.yml")) {
            Path file = configuration.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try (Reader reader = Files.newBufferedReader(file)) {
                result.addAll(parse(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader), minecraftVersion));
            } catch (RuntimeException failure) {
                throw new IOException("Cannot read retro-generation rules from " + file, failure);
            }
        }
        return List.copyOf(result);
    }

    static List<RetroGenerationRule> parse(Object document, String version) {
        if (!(document instanceof Map<?, ?> root)) return List.of();
        Object entries = root.containsKey("placed-features") ? root.get("placed-features") : root.get("placed_features");
        if (!(entries instanceof Map<?, ?> features)) return List.of();
        List<RetroGenerationRule> result = new ArrayList<>();
        for (var entry : features.entrySet()) {
            if (!(entry.getKey() instanceof String id) || !id.startsWith("farmersdelight:")
                    || !PLANTS.contains(id.substring("farmersdelight:".length()))
                    || !(entry.getValue() instanceof Map<?, ?> rule)) continue;
            Set<String> dimensions = strings(rule, "environment", "environments", "dimension_type", "dimension_types", "dimension-type", "dimension-types");
            if (!dimensions.isEmpty() && !dimensions.equals(Set.of("minecraft:overworld"))) continue;
            // Additional dimension filters can target custom dimensions with NORMAL environment.
            // Their identity cannot be inferred from a Bukkit world name: keep those rules out of backfill.
            if (!strings(rule, "dimension", "dimensions").isEmpty()) continue;
            Set<String> biomes = strings(rule, "biome", "biomes");
            // Unknown tag expressions must not silently expand to every biome.
            if (biomes.isEmpty() || biomes.stream().anyMatch(biome -> !biome.matches("minecraft:[a-z0-9_/]+"))) continue;
            Object placement = selectVersion(rule.get("placement"), version);
            Object feature = selectVersion(rule.get("feature"), version);
            Set<Material> soils = placementSoils(placement);
            int rarity = rarity(placement);
            Patch patch = patch(placement, feature);
            if (soils == null || patch == null || !simplePlant(feature, id)) continue;
            // Before 26.1 the predicate belongs to the nested random-patch feature.
            if (feature instanceof Map<?, ?> map && "minecraft:random_patch".equals(map.get("type"))) {
                Object config = map.get("config");
                if (!(config instanceof Map<?, ?> legacyPatch) || !(legacyPatch.get("feature") instanceof Map<?, ?> nested)) continue;
                Set<Material> inner = placementSoils(nested.get("placement"));
                if (inner == null) continue;
                if (soils.isEmpty()) soils = inner;
                else if (!inner.isEmpty()) soils.retainAll(inner);
            }
            if (rarity < 1 || soils.isEmpty()) continue;
            result.add(new RetroGenerationRule(id, Set.copyOf(biomes), strings(rule, "world", "worlds"),
                    Set.copyOf(soils), rarity, patch));
        }
        return result;
    }

    private static Object selectVersion(Object value, String version) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().noneMatch(k -> k.toString().startsWith("$$"))) {
            return value;
        }
        String branch = "$$fallback";
        String[] parts = version.split("\\.");
        if (parts.length >= 2 && Integer.parseInt(parts[0]) >= 26) {
            branch = Integer.parseInt(parts[0]) > 26 || Integer.parseInt(parts[1]) >= 3
                    ? "$$>=26.3" : "$$26.1~26.2";
        }
        return map.containsKey(branch) ? map.get(branch) : map.get("$$fallback");
    }

    private static Set<String> strings(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String text) return Set.of(text);
            if (value instanceof List<?> list) {
                Set<String> result = new LinkedHashSet<>();
                for (Object member : list) {
                    if (!(member instanceof String text)) throw new IllegalArgumentException("Invalid " + key);
                    result.add(text);
                }
                return Set.copyOf(result);
            }
            if (value != null) throw new IllegalArgumentException("Invalid " + key);
        }
        return Set.of();
    }

    private static int rarity(Object placement) {
        if (!(placement instanceof List<?> modifiers)) return -1;
        for (Object modifier : modifiers) {
            if (modifier instanceof Map<?, ?> map && "minecraft:rarity_filter".equals(map.get("type"))
                    && map.get("chance") instanceof Number value && value.doubleValue() == value.intValue()) {
                return value.intValue();
            }
        }
        return -1;
    }

    private static Patch patch(Object placement, Object feature) {
        if (!(placement instanceof List<?> modifiers) || !(feature instanceof Map<?, ?> map)) return null;
        List<String> types = new ArrayList<>();
        for (Object modifier : modifiers) {
            if (!(modifier instanceof Map<?, ?> entry) || !(entry.get("type") instanceof String type)) return null;
            types.add(type);
        }
        boolean legacy = "minecraft:random_patch".equals(map.get("type"));
        List<String> expected = legacy
                ? List.of("minecraft:rarity_filter", "minecraft:in_square", "minecraft:heightmap", "minecraft:biome")
                : List.of("minecraft:rarity_filter", "minecraft:in_square", "minecraft:heightmap", "minecraft:count",
                        "minecraft:offset", "minecraft:block_predicate_filter", "minecraft:biome");
        if (!types.stream().map(type -> type.equals("minecraft:random_offset") ? "minecraft:offset" : type).toList().equals(expected)) return null;
        if (!(modifiers.get(2) instanceof Map<?, ?> height) || !"MOTION_BLOCKING".equals(height.get("heightmap"))) return null;
        try {
            if (legacy) {
                if (!(map.get("config") instanceof Map<?, ?> config)) return null;
                return new Patch(integer(config.get("tries")), integer(config.get("xz_spread")), integer(config.get("y_spread")), false);
            }
            Map<?, ?> count = (Map<?, ?>) modifiers.get(3), offset = (Map<?, ?>) modifiers.get(4);
            boolean modern = "minecraft:offset".equals(offset.get("type"));
            int x = triangle(offset.get(modern ? "x" : "xz_spread"));
            int z = triangle(offset.get(modern ? "z" : "xz_spread"));
            if (x != z) return null;
            return new Patch(integer(count.get("count")), x, triangle(offset.get(modern ? "y" : "y_spread")), true);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int triangle(Object raw) {
        if (!(raw instanceof Map<?, ?> provider) || !"minecraft:trapezoid".equals(provider.get("type"))
                || integer(provider.get("plateau")) != 0) throw new IllegalArgumentException("Unsupported offset provider");
        int max = integer(provider.get("max"));
        if (integer(provider.get("min")) != -max) throw new IllegalArgumentException("Asymmetric patch offset");
        return max;
    }

    private static int integer(Object raw) {
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("Plant patch needs integer parameters");
        }
        return number.intValue();
    }

    private static boolean simplePlant(Object feature, String id) {
        if (!(feature instanceof Map<?, ?> map)) return false;
        if ("minecraft:random_patch".equals(map.get("type"))) {
            return map.get("config") instanceof Map<?, ?> patch && patch.get("feature") instanceof Map<?, ?> nested
                    && simplePlant(nested.get("feature"), id);
        }
        if (!"minecraft:simple_block".equals(map.get("type"))) return false;
        Map<?, ?> config = map.get("config") instanceof Map<?, ?> legacy ? legacy : map;
        if (!(config.get("to_place") instanceof Map<?, ?> provider)
                || !(provider.get("state") instanceof Map<?, ?> state)) return false;
        if (!Set.of("minecraft:simple", "minecraft:simple_state_provider").contains(provider.get("type"))) return false;
        return id.equals(state.get("id")) || id.equals(state.get("Name"));
    }

    /** null means an unsupported constraint; an empty set means no ground constraint at this level. */
    private static Set<Material> placementSoils(Object placement) {
        if (!(placement instanceof List<?> modifiers)) return null;
        Set<Material> soils = new LinkedHashSet<>();
        boolean constrained = false;
        for (Object modifier : modifiers) {
            if (!(modifier instanceof Map<?, ?> map) || !(map.get("type") instanceof String type)) return null;
            if ("minecraft:block_predicate_filter".equals(type)) {
                Set<Material> predicate = predicateSoils(map.get("predicate"));
                if (predicate == null) return null;
                if (!predicate.isEmpty()) {
                    if (constrained) soils.retainAll(predicate);
                    else soils.addAll(predicate);
                    constrained = true;
                    if (soils.isEmpty()) return null;
                }
            } else if (!Set.of("minecraft:rarity_filter", "minecraft:in_square", "minecraft:heightmap",
                    "minecraft:count", "minecraft:offset", "minecraft:random_offset", "minecraft:biome").contains(type)) {
                return null;
            }
        }
        return soils;
    }

    private static Set<Material> predicateSoils(Object predicate) {
        if (!(predicate instanceof Map<?, ?> map)) return null;
        if ("minecraft:all_of".equals(map.get("type"))) {
            if (!(map.get("predicates") instanceof List<?> predicates)) return null;
            Set<Material> soils = new LinkedHashSet<>();
            boolean constrained = false;
            for (Object child : predicates) {
                Set<Material> ground = predicateSoils(child);
                if (ground == null) return null;
                if (!ground.isEmpty()) {
                    if (constrained) soils.retainAll(ground);
                    else soils.addAll(ground);
                    constrained = true;
                    if (soils.isEmpty()) return null;
                }
            }
            return soils;
        }
        if ("minecraft:matching_blocks".equals(map.get("type"))
                && List.of(0, -1, 0).equals(map.get("offset"))) {
            Set<Material> soils = new LinkedHashSet<>();
            Object blocks = map.get("blocks");
            for (Object name : blocks instanceof List<?> list ? list : List.of(blocks == null ? "" : blocks)) {
                Material material = Material.matchMaterial(name.toString());
                if (material == null) return null;
                soils.add(material);
            }
            return soils.isEmpty() ? null : soils;
        }
        Object offset = map.get("offset");
        if (offset != null && !List.of(0, 0, 0).equals(offset)) return null;
        if ("minecraft:replaceable".equals(map.get("type"))) return new LinkedHashSet<>();
        if ("minecraft:matching_fluids".equals(map.get("type"))
                && "minecraft:empty".equals(map.get("fluids"))) return new LinkedHashSet<>();
        // Inverse, tag, height, adjacency and other predicates must not be silently ignored.
        return null;
    }
}
