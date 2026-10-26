package com.huidu.farmersdelight.worldgen;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationProtectionTest {
    @Test void unknownProtectionPluginPausesEverything() {
        var guard = RetroGenerationProtection.fromDetected(List.of(plugin("Towny", null)), List.of(), ignored -> { });
        assertFalse(guard.healthy());
        assertEquals(List.of("Towny"), guard.blocked());
        assertFalse(guard.unclaimed(new Location(null, 0, 64, 0)));
    }

    @Test void discoveryFailureCannotFallThroughToUnprotectedGeneration() {
        var guard = RetroGenerationProtection.fromDetected(List.of(), List.of("CE discovery failed"), ignored -> { });
        assertFalse(guard.healthy());
        assertFalse(guard.unclaimed(new Location(null, 0, 64, 0)));
    }

    @Test void residenceClaimExistsCheckHasNoPlayerOrOpBypass() {
        ResidenceManager manager = new ResidenceManager();
        var guard = RetroGenerationProtection.fromDetected(List.of(plugin("Residence", manager)), List.of(), ignored -> { });
        assertTrue(guard.healthy());
        assertTrue(guard.unclaimed(new Location(null, 0, 64, 0)));
        manager.claimed = true;
        assertFalse(guard.unclaimed(new Location(null, 0, 64, 0)));
        assertTrue(guard.healthy(), "a claim should skip a location without stopping unrelated chunks");
    }

    @Test void exceptionPermanentlyPausesUntilRediscoveryAndWarnsOnce() {
        ResidenceManager manager = new ResidenceManager();
        List<String> warnings = new ArrayList<>();
        var guard = RetroGenerationProtection.fromDetected(List.of(plugin("Residence", manager)), List.of(), warnings::add);
        manager.fail = true;
        assertFalse(guard.unclaimed(new Location(null, 0, 64, 0)));
        manager.fail = false;
        assertFalse(guard.unclaimed(new Location(null, 0, 64, 0)));
        assertFalse(guard.healthy());
        assertEquals(1, warnings.size());
    }

    private static Plugin plugin(String name, ResidenceManager manager) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{ResidencePlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    case "getResidenceManager" -> manager;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public interface ResidencePlugin extends Plugin { ResidenceManager getResidenceManager(); }
    public static class ResidenceManager {
        boolean claimed, fail;
        public Object getByLoc(Location location) {
            if (fail) throw new IllegalStateException("claim data is not available");
            return claimed ? this : null;
        }
    }
}
