package com.huidu.farmersdelight.worldgen;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationSettingsTest {
    @Test void defaultsAreOptInAndPrimaryWorldOnly() {
        var settings = RetroGenerationSettings.load(new MemoryConfiguration(), "survival");
        assertFalse(settings.enabled());
        assertEquals(Set.of("survival"), settings.worlds());
        assertEquals(1, settings.chunksPerTick());
        assertEquals(1.0, settings.densityMultiplier());
        assertTrue(settings.excludes("survival", 128, -128, 0, 0));
        assertFalse(settings.excludes("survival", 129, 0, 0, 0));
    }

    @Test void coordinateExclusionsAreInclusiveAndWorldSpecific() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("retro-generation.worlds", List.of("survival", "world2"));
        config.set("retro-generation.exclude-spawn-radius", 0);
        config.set("retro-generation.excluded-areas", List.of(Map.of("world", "survival",
                "min-x", -200, "max-x", -100, "min-z", 400, "max-z", 500)));
        var settings = RetroGenerationSettings.load(config, "world");
        assertEquals(Set.of("survival", "world2"), settings.worlds());
        assertTrue(settings.excludes("survival", -200, 500, 0, 0));
        assertFalse(settings.excludes("world2", -200, 500, 0, 0));
        assertFalse(settings.excludes("survival", -201, 500, 0, 0));
    }

    @Test void malformedSettingsNeverExpandScopeOrDensity() {
        for (var entry : List.of(Map.entry("worlds", "world"), Map.entry("enabled", "yes"),
                Map.entry("worlds", List.of(12)), Map.entry("chunks-per-tick", 5),
                Map.entry("attempts-per-plant", 65), Map.entry("max-plants-per-chunk", 0),
                Map.entry("density-multiplier", Double.NaN), Map.entry("density-multiplier", "lots"),
                Map.entry("exclude-spawn-radius", -1), Map.entry("seed", 1.5),
                Map.entry("excluded-areas", "not a list"))) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("retro-generation." + entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> RetroGenerationSettings.load(config, "world"),
                    entry.getKey());
        }
    }

    @Test void malformedCoordinatesAreRejected() {
        for (Object max : List.of(-201, 1.2, "500", Long.MAX_VALUE)) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("retro-generation.excluded-areas", List.of(Map.of("world", "world", "min-x", -200,
                    "max-x", max, "min-z", 0, "max-z", 20)));
            assertThrows(IllegalArgumentException.class, () -> RetroGenerationSettings.load(config, "world"));
        }
    }
}
