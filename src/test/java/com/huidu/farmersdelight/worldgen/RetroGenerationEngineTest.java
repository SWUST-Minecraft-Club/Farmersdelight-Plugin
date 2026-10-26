package com.huidu.farmersdelight.worldgen;

import org.bukkit.Material;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationEngineTest {
    private static final RetroGenerationRule RULE = new RetroGenerationRule("farmersdelight:wild_onions",
            Set.of("minecraft:plains"), Set.of(), Set.of(Material.GRASS_BLOCK), 1);
    private static final RetroGenerationEngine.Candidate CANDIDATE = new RetroGenerationEngine.Candidate(RULE, 7, 65, 7);

    @Test void previewIsReadOnlyAndDeterministic() {
        Terrain terrain = new Terrain();
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
        assertFalse(plan.candidates().isEmpty());
        assertEquals(plan, RetroGenerationEngine.plan(terrain, settings(), List.of(RULE)));
        assertFalse(terrain.processed);
        assertEquals(0, terrain.writes.size());
    }

    @Test void commitMarksBeforeAddingAndCannotRunTwice() {
        Terrain terrain = new Terrain();
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
        int count = RetroGenerationEngine.commit(terrain, settings(), plan);
        assertTrue(count > 0);
        assertEquals(count, terrain.writes.size());
        assertEquals(0, RetroGenerationEngine.commit(terrain, settings(), plan));
        assertEquals("processed", RetroGenerationEngine.plan(terrain, settings(), List.of(RULE)).reason());
    }

    @Test void emptyRandomPassIsAlsoMarkedOnce() {
        Terrain terrain = new Terrain();
        var rare = new RetroGenerationRule(RULE.id(), RULE.biomes(), Set.of(), RULE.soils(), Integer.MAX_VALUE);
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(rare));
        assertTrue(plan.candidates().isEmpty());
        RetroGenerationEngine.commit(terrain, settings(), plan);
        assertTrue(terrain.processed);
    }

    @Test void interruptionAfterFirstWriteLeavesACompletionMark() {
        Terrain terrain = new Terrain() {
            @Override public boolean place(RetroGenerationEngine.Candidate candidate) {
                boolean result = super.place(candidate);
                active = false;
                return result;
            }
        };
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
        assertEquals(1, RetroGenerationEngine.commit(terrain, settings(), plan));
        assertTrue(terrain.processed);
        terrain.active = true;
        assertEquals(0, RetroGenerationEngine.commit(terrain, settings(), plan));
    }

    @Test void neverOverwritesGrassFlowersWaterCropsOrAirVariants() {
        for (Material occupied : List.of(Material.WATER, Material.SHORT_GRASS, Material.POPPY,
                Material.WHEAT, Material.CHEST, Material.CAVE_AIR, Material.VOID_AIR, Material.STONE)) {
            Terrain terrain = new Terrain();
            terrain.blocks.put(new Pos(7, 65, 7), occupied);
            assertEquals(RetroGenerationEngine.Rejection.OCCUPIED, rejection(terrain), occupied.name());
        }
    }

    @Test void naturalLookingCustomBlocksAndStaleAirDataAreRejected() {
        for (int y : List.of(64, 65, 66)) {
            Terrain terrain = new Terrain();
            terrain.custom.add(new Pos(7, y, 7));
            assertNotNull(rejection(terrain));
        }
    }

    @Test void rejectsFarmlandAndPathsEvenWhenRuleAllowsThem() {
        for (Material soil : List.of(Material.FARMLAND, Material.DIRT_PATH, Material.STONE)) {
            Terrain terrain = new Terrain();
            terrain.blocks.put(new Pos(7, 64, 7), soil);
            var rule = new RetroGenerationRule(RULE.id(), RULE.biomes(), Set.of(), Set.of(soil), 1);
            assertEquals(RetroGenerationEngine.Rejection.SOIL, RetroGenerationEngine.rejection(terrain, settings(),
                    new RetroGenerationEngine.Candidate(rule, 7, 65, 7)));
        }
    }

    @Test void rejectsBuildingsRoofsAndClaims() {
        Terrain terrain = new Terrain();
        terrain.blocks.put(new Pos(10, 65, 7), Material.OAK_PLANKS);
        assertEquals(RetroGenerationEngine.Rejection.BUILDING, rejection(terrain));
        terrain.blocks.clear();
        terrain.sky = false;
        assertEquals(RetroGenerationEngine.Rejection.BUILDING, rejection(terrain));
        terrain.sky = true;
        terrain.claimed = true;
        assertEquals(RetroGenerationEngine.Rejection.PROTECTED, rejection(terrain));
    }

    @Test void containerAnywhereInChunkStopsThePassWithoutMarking() {
        Terrain terrain = new Terrain();
        terrain.blockEntities = true;
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
        assertEquals("block_entities", plan.reason());
        assertEquals(0, RetroGenerationEngine.commit(terrain, settings(), plan));
        assertFalse(terrain.processed);
    }

    @Test void rechecksBeforeEveryWriteIfWorldChangedAfterPreview() {
        Terrain terrain = new Terrain();
        var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
        for (var candidate : plan.candidates()) {
            terrain.blocks.put(new Pos(candidate.x(), candidate.y(), candidate.z()), Material.CHEST);
        }
        assertEquals(0, RetroGenerationEngine.commit(terrain, settings(), plan));
        assertTrue(terrain.blocks.values().stream().allMatch(type -> type == Material.CHEST));
    }

    @Test void disallowedWorldBiomeAndInactiveTerrainNeverWrite() {
        Terrain terrain = new Terrain();
        terrain.world = "other";
        assertEquals("world", RetroGenerationEngine.plan(terrain, settings(), List.of(RULE)).reason());
        terrain.world = "world";
        terrain.biome = "minecraft:desert";
        assertTrue(RetroGenerationEngine.plan(terrain, settings(), List.of(RULE)).candidates().isEmpty());
        terrain.active = false;
        assertEquals("unavailable", RetroGenerationEngine.plan(terrain, settings(), List.of(RULE)).reason());
        assertFalse(terrain.processed);
        assertTrue(terrain.writes.isEmpty());
    }

    @Test void allReadsAndWritesStayInOneChunkIncludingNegativeCoordinates() {
        for (int cx : List.of(-101, -1, 0, 1, 91)) {
            Terrain terrain = new Terrain();
            terrain.cx = cx;
            terrain.cz = -29;
            var plan = RetroGenerationEngine.plan(terrain, settings(), List.of(RULE));
            assertTrue(RetroGenerationEngine.commit(terrain, settings(), plan) > 0);
        }
    }

    @Test void worldFilterIsHonouredAndDensityDoesNotBypassBiome() {
        Terrain terrain = new Terrain();
        var otherWorld = new RetroGenerationRule(RULE.id(), RULE.biomes(), Set.of("other"), RULE.soils(), 1);
        assertTrue(RetroGenerationEngine.plan(terrain, settings(), List.of(otherWorld)).candidates().isEmpty());
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("retro-generation.density-multiplier", 1000);
        config.set("retro-generation.exclude-spawn-radius", 0);
        terrain.biome = "minecraft:desert";
        assertTrue(RetroGenerationEngine.plan(terrain, RetroGenerationSettings.load(config, "world"), List.of(RULE))
                .candidates().isEmpty());
    }

    private static RetroGenerationEngine.Rejection rejection(Terrain terrain) {
        return RetroGenerationEngine.rejection(terrain, settings(), CANDIDATE);
    }

    private static RetroGenerationSettings settings() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("retro-generation.exclude-spawn-radius", 0);
        return RetroGenerationSettings.load(config, "world");
    }

    private record Pos(int x, int y, int z) { }
    private static class Terrain implements RetroGenerationEngine.Terrain {
        final Map<Pos, Material> blocks = new HashMap<>();
        final Set<Pos> custom = new HashSet<>();
        final List<Pos> writes = new ArrayList<>();
        boolean processed, blockEntities, claimed;
        boolean active = true, sky = true;
        int cx = 32, cz = -19;
        String world = "world", biome = "minecraft:plains";
        @Override public int chunkX() { return cx; }
        @Override public int chunkZ() { return cz; }
        @Override public int minHeight() { return -64; }
        @Override public int maxHeight() { return 320; }
        @Override public long worldSeed() { return 123456789L; }
        @Override public String worldName() { return world; }
        @Override public int spawnX() { return 0; }
        @Override public int spawnZ() { return 0; }
        @Override public int surfaceY(int x, int z) { bounds(x, z); return 64; }
        @Override public Material type(int x, int y, int z) {
            bounds(x, z);
            return blocks.getOrDefault(new Pos(x, y, z), y == 64 ? Material.GRASS_BLOCK : y < 64 ? Material.DIRT : Material.AIR);
        }
        @Override public boolean custom(int x, int y, int z) { bounds(x, z); return custom.contains(new Pos(x, y, z)); }
        @Override public String biome(int x, int y, int z) { bounds(x, z); return biome; }
        @Override public boolean skyExposed(int x, int y, int z) { bounds(x, z); return sky; }
        @Override public boolean unclaimed(int x, int y, int z) { bounds(x, z); return !claimed; }
        @Override public boolean hasBlockEntities() { return blockEntities; }
        @Override public boolean processed() { return processed; }
        @Override public void markProcessed() { assertFalse(processed); processed = true; }
        @Override public boolean active() { return active; }
        @Override public boolean place(RetroGenerationEngine.Candidate candidate) {
            assertTrue(processed, "completion must be reserved before modifying the map");
            assertEquals(Material.AIR, type(candidate.x(), candidate.y(), candidate.z()));
            writes.add(new Pos(candidate.x(), candidate.y(), candidate.z()));
            return true;
        }
        private static void bounds(int x, int z) { assertTrue(x >= 0 && x <= 15 && z >= 0 && z <= 15); }
    }
}
