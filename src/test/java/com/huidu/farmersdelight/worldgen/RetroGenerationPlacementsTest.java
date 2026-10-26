package com.huidu.farmersdelight.worldgen;

import org.bukkit.Material;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationPlacementsTest {
    @Test void nativeShapeGeneratesAllSixtyFourTriangularOffsetsAroundOneHeightmapAnchor() {
        var settings = RetroGenerationSettings.load(new MemoryConfiguration(), "world");
        var rule = new RetroGenerationRule("farmersdelight:wild_onions", Set.of("minecraft:plains"), Set.of(),
                Set.of(Material.GRASS_BLOCK), 1);
        var terrain = terrain(64);
        var result = RetroGenerationPlacements.sample(terrain, settings, rule);
        assertFalse(result.chanceMissed());
        assertEquals(64, result.positions().size());
        assertEquals(result, RetroGenerationPlacements.sample(terrain, settings, rule));
        assertTrue(result.positions().stream().allMatch(pos -> pos.x() >= -6 && pos.x() <= 21 && pos.z() >= -6 && pos.z() <= 21
                && pos.y() >= 62 && pos.y() <= 68));
        assertTrue(result.positions().stream().mapToInt(RetroGenerationPlacements.Position::y).distinct().count() > 1,
                "vertical offsets are retained rather than reprojecting each attempt to the surface");
        int minX = result.positions().stream().mapToInt(RetroGenerationPlacements.Position::x).min().orElseThrow();
        int maxX = result.positions().stream().mapToInt(RetroGenerationPlacements.Position::x).max().orElseThrow();
        assertTrue(maxX - minX <= 12);
    }

    @Test void configuredCountIsNotReducedByRetiredCapsAndDensityStillControlsPatchChance() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("retro-generation.attempts-per-plant", 1);
        config.set("retro-generation.max-plants-per-chunk", 1);
        config.set("retro-generation.density-multiplier", 1000);
        var settings = RetroGenerationSettings.load(config, "world");
        var rule = new RetroGenerationRule("farmersdelight:wild_tomatoes", Set.of("minecraft:plains"), Set.of(),
                Set.of(Material.GRASS_BLOCK), 100, new RetroGenerationRule.Patch(128, 7, 3, true));
        assertEquals(128, RetroGenerationPlacements.sample(terrain(64), settings, rule).positions().size());
        var rare = new RetroGenerationRule(rule.id(), rule.biomes(), rule.worlds(), rule.soils(), Integer.MAX_VALUE, rule.patch());
        assertTrue(RetroGenerationPlacements.sample(terrain(64), settings, rare).chanceMissed());
    }

    private static RetroGenerationEngine.Terrain terrain(int surfaceY) {
        return (RetroGenerationEngine.Terrain) Proxy.newProxyInstance(RetroGenerationEngine.Terrain.class.getClassLoader(),
                new Class<?>[]{RetroGenerationEngine.Terrain.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "worldSeed" -> 123456789L;
                    case "chunkX" -> 32;
                    case "chunkZ" -> -19;
                    case "surfaceY" -> surfaceY;
                    case "minHeight" -> -64;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
