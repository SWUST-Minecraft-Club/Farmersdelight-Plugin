package com.huidu.farmersdelight.worldgen;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationRuleTest {
    private static final Path CONFIG = Path.of("src/main/resources/craftengine/farmersdelight/configuration");

    @Test void loadsAllSevenLandPlantsAndNeverRiceInEverySupportedBranch() throws Exception {
        for (String version : List.of("1.21.5", "26.1", "26.2", "26.3", "26.3.1")) {
            var rules = RetroGenerationRule.load(CONFIG, version);
            assertEquals(7, rules.size(), version);
            assertTrue(rules.stream().noneMatch(rule -> rule.id().contains("rice")));
            var onions = rules.stream().filter(rule -> rule.id().endsWith(":wild_onions")).findFirst().orElseThrow();
            assertEquals(120, onions.rarity());
            assertTrue(onions.biomes().contains("minecraft:plains"));
            assertTrue(onions.soils().contains(Material.GRASS_BLOCK));
            var cabbage = rules.stream().filter(rule -> rule.id().endsWith(":wild_cabbages")).findFirst().orElseThrow();
            assertEquals(Set.of(Material.SAND, Material.RED_SAND), cabbage.soils());
        }
    }

    @Test void honoursWorldAndDimensionRestrictions() {
        assertEquals(Set.of("survival"), parse("world: [survival]", "").getFirst().worlds());
        assertTrue(parse("dimension: [custom:building]", "").isEmpty());
        assertTrue(parse("environment: [minecraft:the_nether]", "").isEmpty());
        assertTrue(parse("biome: ['#minecraft:is_forest']", "").isEmpty());
    }

    @Test void inverseOrUnknownFiltersCannotBeConvertedIntoAllowedGround() {
        assertTrue(parse("", "type: minecraft:not\npredicate:\n  type: minecraft:matching_blocks\n  blocks: [minecraft:grass_block]\n  offset: [0, -1, 0]").isEmpty());
        assertTrue(parse("", "type: minecraft:would_survive\nstate: {Name: minecraft:grass}").isEmpty());
    }

    @Test void conflictingGroundPredicatesDoNotBecomeAUnion() {
        var parsed = parse("", "type: minecraft:all_of\npredicates:\n- {type: minecraft:matching_blocks, blocks: [minecraft:grass_block], offset: [0, -1, 0]}\n- {type: minecraft:matching_blocks, blocks: [minecraft:sand], offset: [0, -1, 0]}");
        assertTrue(parsed.isEmpty());
    }

    @Test void ignoresMissingFilesAndUnsafeConfiguredFeatures() throws Exception {
        assertTrue(RetroGenerationRule.load(Path.of("build/no-such-pack"), "26.3").isEmpty());
        String yaml = document("", "").replace("minecraft:simple_block", "minecraft:tree");
        assertTrue(RetroGenerationRule.parse(new Yaml().load(yaml), "26.3").isEmpty());
        yaml = document("", "").replace("id: farmersdelight:wild_onions", "id: minecraft:stone");
        assertTrue(RetroGenerationRule.parse(new Yaml().load(yaml), "26.3").isEmpty());
    }

    private static List<RetroGenerationRule> parse(String filters, String predicate) {
        return RetroGenerationRule.parse(new Yaml().load(document(filters, predicate)), "26.3");
    }

    private static String document(String filters, String predicate) {
        if (predicate.isEmpty()) predicate = "type: minecraft:matching_blocks\nblocks: [minecraft:grass_block]\noffset: [0, -1, 0]";
        return "placed-features:\n  farmersdelight:wild_onions:\n"
                + (filters.startsWith("biome:") ? "" : "    biome: [minecraft:plains]\n")
                + (filters.isEmpty() ? "" : filters.indent(4))
                + "    feature:\n      type: minecraft:simple_block\n      to_place:\n        type: minecraft:simple\n        state: {id: farmersdelight:wild_onions}\n"
                + "    placement:\n    - {type: minecraft:rarity_filter, chance: 120}\n"
                + "    - type: minecraft:block_predicate_filter\n      predicate:\n" + predicate.indent(8);
    }
}
