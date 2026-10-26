package com.huidu.farmersdelight.worldgen;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Claim existence queries, without a player, OP bypass, or permission-based fail-open fallback. */
public final class RetroGenerationProtection {
    private static final Set<String> KNOWN = Set.of("BentoBox", "CrashClaim", "Dominion", "FabledSkyBlock",
            "Factions", "FactionsUUID", "GriefDefender", "GriefPrevention", "HuskClaims", "HuskTowns",
            "IridiumSkyblock", "Kingdoms", "KingdomsX", "LandClaimPlugin", "Landlord", "Lands", "NoBuildPlus",
            "PlotSquared", "PreciousStones", "Protect", "ProtectionStones", "RedProtect", "Residence",
            "SaberFactions", "SimpleClaimSystem", "SuperiorSkyblock2", "Towny", "UltimateClaims",
            "UltimateClans", "WorldGuard", "XClaim");
    private final List<Probe> probes;
    private final List<String> blocked;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final Consumer<String> warning;

    private RetroGenerationProtection(List<Probe> probes, List<String> blocked, Consumer<String> warning) {
        this.probes = List.copyOf(probes);
        this.blocked = List.copyOf(blocked);
        this.warning = warning;
    }

    public static RetroGenerationProtection discover(Plugin owner, Set<String> extra, Consumer<String> warning) {
        Map<String, Plugin> plugins = new LinkedHashMap<>();
        for (Plugin plugin : owner.getServer().getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && (KNOWN.stream().anyMatch(name -> name.equalsIgnoreCase(plugin.getName()))
                    || extra.stream().anyMatch(name -> name.equalsIgnoreCase(plugin.getName())))) {
                plugins.put(plugin.getName(), plugin);
            }
        }
        List<String> blocked = new ArrayList<>();
        try {
            // Include third-party CE providers even if this plugin has never heard of their names.
            for (var provider : BukkitCraftEngine.instance().antiGriefProvider().providers()) {
                Plugin plugin = provider.plugin();
                if (plugin != null && plugin.isEnabled()) plugins.put(plugin.getName(), plugin);
            }
        } catch (RuntimeException | LinkageError failure) {
            blocked.add("CraftEngine protection discovery");
        }
        return fromDetected(List.copyOf(plugins.values()), blocked, warning);
    }

    static RetroGenerationProtection fromDetected(List<Plugin> plugins, List<String> discoveryFailures,
                                                   Consumer<String> warning) {
        List<String> blocked = new ArrayList<>(discoveryFailures);
        List<Probe> probes = new ArrayList<>();
        for (Plugin plugin : plugins) {
            try {
                Query query = switch (plugin.getName().toLowerCase(java.util.Locale.ROOT)) {
                    case "residence" -> residence(plugin);
                    case "griefprevention" -> griefPrevention(plugin);
                    case "worldguard" -> worldGuard(plugin);
                    default -> null;
                };
                if (query == null) blocked.add(plugin.getName());
                else probes.add(new Probe(plugin, query));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                blocked.add(plugin.getName() + " (claim API unavailable)");
            }
        }
        return new RetroGenerationProtection(probes, blocked, warning);
    }

    public List<String> blocked() { return blocked; }
    public boolean healthy() { return blocked.isEmpty() && healthy.get(); }

    public boolean unclaimed(Location location) {
        if (!healthy()) return false;
        try {
            for (Probe probe : probes) {
                if (!probe.plugin().isEnabled()) throw new IllegalStateException(probe.plugin().getName() + " disabled");
                if (!probe.query().unclaimed(location)) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (healthy.compareAndSet(true, false)) warning.accept(String.valueOf(failure.getMessage()));
            return false;
        }
    }

    private static Query residence(Plugin plugin) throws ReflectiveOperationException {
        Object manager = plugin.getClass().getMethod("getResidenceManager").invoke(plugin);
        Method query = manager.getClass().getMethod("getByLoc", Location.class);
        return location -> query.invoke(manager, location) == null;
    }

    private static Query griefPrevention(Plugin plugin) throws ReflectiveOperationException {
        Object store = plugin.getClass().getField("dataStore").get(plugin);
        Class<?> claim = Class.forName("me.ryanhamshire.GriefPrevention.Claim", false, plugin.getClass().getClassLoader());
        Method query = store.getClass().getMethod("getClaimAt", Location.class, boolean.class, claim);
        // Ignore claim height so a plant cannot appear over or under an existing player's claim.
        return location -> query.invoke(store, location, true, null) == null;
    }

    private static Query worldGuard(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> guard = Class.forName("com.sk89q.worldguard.WorldGuard", false, loader);
        Object instance = guard.getMethod("getInstance").invoke(null);
        Object platform = guard.getMethod("getPlatform").invoke(instance);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", false, loader);
        Method adapt = adapter.getMethod("adapt", World.class);
        Class<?> weWorld = Class.forName("com.sk89q.worldedit.world.World", false, loader);
        Class<?> containerType = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", false, loader);
        Method get = containerType.getMethod("get", weWorld);
        Class<?> vector = Class.forName("com.sk89q.worldedit.math.BlockVector3", false, loader);
        Method at = vector.getMethod("at", int.class, int.class, int.class);
        Class<?> managerType = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", false, loader);
        Method regions = managerType.getMethod("getApplicableRegions", vector);
        Method global = managerType.getMethod("getRegion", String.class);
        Class<?> setType = Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet", false, loader);
        Method size = setType.getMethod("size");
        return location -> {
            Object manager = get.invoke(container, adapt.invoke(null, location.getWorld()));
            if (manager == null) throw new IllegalStateException("WorldGuard region data unavailable for "
                    + location.getWorld().getName());
            if (global.invoke(manager, "__global__") != null) return false;
            Object set = regions.invoke(manager, at.invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            return ((Number) size.invoke(set)).intValue() == 0;
        };
    }

    @FunctionalInterface
    private interface Query { boolean unclaimed(Location location) throws ReflectiveOperationException; }
    private record Probe(Plugin plugin, Query query) { }
}
