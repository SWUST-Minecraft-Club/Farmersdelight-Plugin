package com.huidu.farmersdelight;

import com.huidu.farmersdelight.worldgen.RetroGenerationEngine;
import com.huidu.farmersdelight.worldgen.RetroGenerationPlacements;
import com.huidu.farmersdelight.worldgen.RetroGenerationRule;
import com.huidu.farmersdelight.worldgen.RetroGenerationSettings;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.core.HolderProxy;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerChunkCacheProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.util.RandomSourceProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.WorldGenLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.levelgen.placement.PlacementContextProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.levelgen.placement.PlacementModifierProxy;
import org.bukkit.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Runs only the read-only coordinate modifiers, never a Feature, predicate or world writer. */
final class RetroGenerationNativePlacements {
    private RetroGenerationNativePlacements() { }

    static RetroGenerationPlacements.Result sample(World world, RetroGenerationEngine.Terrain terrain,
                                                   RetroGenerationSettings settings, RetroGenerationRule rule) {
        // The older random_patch feature has no standalone coordinate modifier chain. Its equivalent
        // supported shape is sampled in Java; the 26.3 flat chain uses the actual loaded native objects.
        if (!rule.patch().flat()) return RetroGenerationPlacements.sample(terrain, settings, rule);
        try {
            Object holder = BukkitWorldManager.instance().placedFeatureHolderById(Key.of(rule.id()));
            if (holder == null) throw new IllegalStateException("Placed feature not loaded: " + rule.id());
            Object feature = HolderProxy.INSTANCE.value(holder);
            @SuppressWarnings("unchecked") List<Object> modifiers = (List<Object>) Bindings.PLACEMENTS.get(feature.getClass()).invoke(feature);
            List<String> expected = List.of("RarityFilter", "InSquarePlacement", "HeightmapPlacement", "CountPlacement");
            if (modifiers.size() != 7) throw new IllegalStateException("Changed native patch: " + rule.id());
            for (int i = 0; i < expected.size(); i++) {
                if (!modifiers.get(i).getClass().getSimpleName().equals(expected.get(i))) {
                    throw new IllegalStateException("Changed native placement order: " + rule.id());
                }
            }
            String offset = modifiers.get(4).getClass().getSimpleName();
            if (!offset.equals("OffsetPlacement") && !offset.equals("RandomOffsetPlacement")) {
                throw new IllegalStateException("Unsupported native plant offset: " + rule.id());
            }
            Object level = CraftWorldProxy.INSTANCE.getWorld(world);
            Object generator = ServerChunkCacheProxy.INSTANCE.getGenerator(ServerLevelProxy.INSTANCE.getChunkSource(level));
            Object context = Bindings.CONTEXT.newInstance(level, generator, Optional.empty());
            Object random = Bindings.CREATE_RANDOM.invoke(null, RetroGenerationPlacements.seed(terrain, settings, rule));
            List<Object> positions = List.of(BlockPosProxy.INSTANCE.newInstance(terrain.chunkX() << 4, 0, terrain.chunkZ() << 4));
            int start = 0;
            if (settings.densityMultiplier() != 1.0) {
                float sample = (float) Bindings.NEXT_FLOAT.invoke(random);
                if (sample >= Math.min(1.0f, (float) settings.densityMultiplier() / rule.rarity())) {
                    return new RetroGenerationPlacements.Result(List.of(), true);
                }
                start = 1;
            }
            for (int i = start; i < 5; i++) {
                List<Object> next = new ArrayList<>();
                for (Object position : positions) {
                    Consumer<Object> collect = value -> {
                        if (next.size() >= 256) throw new IllegalStateException("Native patch exceeds bounded count");
                        next.add(value);
                    };
                    if (Bindings.MODIFY != null) Bindings.MODIFY.invoke(modifiers.get(i), context, random, position, collect);
                    else {
                        try (var stream = (java.util.stream.Stream<?>) Bindings.GET_POSITIONS.invoke(modifiers.get(i), context, random, position)) {
                            stream.forEach(collect);
                        }
                    }
                }
                if (i == 0 && next.isEmpty()) return new RetroGenerationPlacements.Result(List.of(), true);
                positions = next;
            }
            List<RetroGenerationPlacements.Position> result = new ArrayList<>();
            for (Object position : positions) result.add(new RetroGenerationPlacements.Position(
                    Vec3iProxy.INSTANCE.getX(position) - (terrain.chunkX() << 4), Vec3iProxy.INSTANCE.getY(position),
                    Vec3iProxy.INSTANCE.getZ(position) - (terrain.chunkZ() << 4)));
            return new RetroGenerationPlacements.Result(List.copyOf(result), false);
        } catch (ReflectiveOperationException failure) {
            // Refuse this chunk without marking it; never silently switch distributions on a target server.
            throw new IllegalStateException("Cannot read native plant placements for " + rule.id(), failure);
        }
    }

    private static final class Bindings {
        static final Constructor<?> CONTEXT;
        static final Method CREATE_RANDOM, NEXT_FLOAT, MODIFY, GET_POSITIONS;
        static final ClassValue<Method> PLACEMENTS = new ClassValue<>() {
            @Override protected Method computeValue(Class<?> type) {
                try { return type.getMethod("placement"); }
                catch (NoSuchMethodException failure) { throw new IllegalStateException("Native feature has no placement accessor", failure); }
            }
        };
        static {
            try {
                CONTEXT = java.util.Arrays.stream(PlacementContextProxy.CLASS.getConstructors())
                        .filter(constructor -> constructor.getParameterCount() == 3
                                && constructor.getParameterTypes()[0] == WorldGenLevelProxy.CLASS
                                && constructor.getParameterTypes()[2] == Optional.class)
                        .findFirst().orElseThrow(() -> new NoSuchMethodException("PlacementContext constructor"));
                CREATE_RANDOM = RandomSourceProxy.CLASS.getMethod("create", long.class);
                NEXT_FLOAT = RandomSourceProxy.CLASS.getMethod("nextFloat");
                Method modify = null, positions = null;
                try {
                    modify = PlacementModifierProxy.CLASS.getMethod("modify", PlacementContextProxy.CLASS,
                            RandomSourceProxy.CLASS, BlockPosProxy.CLASS, Consumer.class);
                } catch (NoSuchMethodException olderServer) {
                    positions = PlacementModifierProxy.CLASS.getMethod("getPositions", PlacementContextProxy.CLASS,
                            RandomSourceProxy.CLASS, BlockPosProxy.CLASS);
                }
                MODIFY = modify;
                GET_POSITIONS = positions;
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
