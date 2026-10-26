package com.huidu.farmersdelight;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.worldgen.RetroGenerationSearch;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** One loaded chunk per tick across all searches, always read on its owning region. */
final class RetroGenerationLocator {
    private final FarmersDelightPlugin plugin;
    private final CraftEngineResourceLease lease;
    private final BooleanSupplier ready;
    private final Map<UUID, Search> active = new ConcurrentHashMap<>();
    private final Queue<Search> queue = new ConcurrentLinkedQueue<>();

    RetroGenerationLocator(FarmersDelightPlugin plugin, CraftEngineResourceLease lease, BooleanSupplier ready) {
        this.plugin = plugin;
        this.lease = lease;
        this.ready = ready;
    }

    /** Called on the player's owning region; captures the origin before switching threads. */
    synchronized void start(Player player) {
        if (!ready.getAsBoolean()) {
            player.sendMessage(I18n.getComponent("retrogen.locate_unavailable", player));
            return;
        }
        if (active.containsKey(player.getUniqueId()) || active.size() >= 4) {
            player.sendMessage(I18n.getComponent("retrogen.locate_busy", player));
            return;
        }
        Location origin = player.getLocation();
        Search search = new Search(player, origin.getWorld(), origin.getBlockX(), origin.getBlockZ());
        active.put(player.getUniqueId(), search);
        player.sendMessage(I18n.getComponent("retrogen.locate_started", player,
                Map.of("world", search.world.getName(), "radius", String.valueOf(RetroGenerationSearch.RADIUS))));
        plugin.scheduler().run(() -> {
            if (active.get(search.playerId) != search) return;
            // Snapshot coordinates only. No chunk is loaded or kept loaded by this command.
            search.chunks = Arrays.stream(search.world.getLoadedChunks())
                    .filter(chunk -> search.index.includesChunk(chunk.getX(), chunk.getZ()))
                    .map(chunk -> new ChunkPosition(chunk.getX(), chunk.getZ())).toList();
            queue.add(search);
        });
    }

    void tick() {
        Search search = queue.poll();
        if (search == null || active.get(search.playerId) != search) return;
        if (!ready.getAsBoolean() || search.next >= search.chunks.size()) {
            if (!ready.getAsBoolean()) search.skipped += search.chunks.size() - search.next;
            finish(search);
            return;
        }
        ChunkPosition chunk = search.chunks.get(search.next++);
        plugin.scheduler().runAt(search.world, chunk.x(), chunk.z(), () -> scan(search, chunk));
    }

    private void scan(Search search, ChunkPosition position) {
        if (active.get(search.playerId) != search) return;
        try (AutoCloseable acquired = lease.tryAcquire()) {
            if (acquired == null || !ready.getAsBoolean() || !search.world.isChunkLoaded(position.x(), position.z())) {
                search.skipped++;
                return;
            }
            var world = CustomBlockUtils.getCEWorld(search.world);
            var chunk = world == null ? null : world.getChunkAtIfLoaded(position.x(), position.z());
            if (chunk == null) { search.skipped++; return; }
            for (var section : chunk.sections()) {
                if (section == null || section.isEmpty()
                        || !section.statesContainer.hasAny(state -> RetroGenerationSearch.isWildPlant(id(state)))) continue;
                search.index.scanSection(position.x(), position.z(), section.sectionY, i -> id(section.getBlockState(i)));
            }
            search.checked++;
        } catch (Exception | LinkageError failure) {
            search.skipped++;
            if (search.error == null) search.error = String.valueOf(failure.getMessage());
        } finally {
            // Queue publication transfers ownership of these fields back to the global dispatcher.
            if (active.get(search.playerId) == search) queue.add(search);
        }
    }

    private static String id(ImmutableBlockState state) {
        return state == null || state.isEmpty() ? null : state.owner().value().id().toString();
    }

    private void finish(Search search) {
        plugin.scheduler().runForEntity(search.player, () -> {
            try {
                search.player.sendMessage(I18n.getComponent("retrogen.locate_result", search.player, Map.of(
                        "world", search.world.getName(), "checked", String.valueOf(search.checked),
                        "skipped", String.valueOf(search.skipped), "count", String.valueOf(search.index.count()))));
                for (var position : search.index.nearest()) {
                    search.player.sendMessage(I18n.getComponent("retrogen.locate_candidate", search.player, Map.of(
                            "id", position.id(), "x", String.valueOf(position.x()), "y", String.valueOf(position.y()),
                            "z", String.valueOf(position.z()),
                            "distance", String.valueOf(Math.round(Math.sqrt(search.index.distanceSquared(position)))))));
                }
                if (search.index.count() == 0) {
                    search.player.sendMessage(I18n.getComponent("retrogen.locate_empty", search.player));
                }
                if (search.error != null) search.player.sendMessage(I18n.getComponent("retrogen.failed", search.player,
                        Map.of("error", search.error)));
            } finally {
                active.remove(search.playerId, search);
            }
        }, () -> active.remove(search.playerId, search));
    }

    void stop() { active.clear(); queue.clear(); }

    void cancelPlayer(UUID player) { active.remove(player); }

    void cancelWorld(UUID world) {
        active.values().removeIf(search -> search.world.getUID().equals(world));
        queue.removeIf(search -> search.world.getUID().equals(world));
    }

    private record ChunkPosition(int x, int z) { }

    private static final class Search {
        final Player player;
        final UUID playerId;
        final World world;
        final RetroGenerationSearch index;
        List<ChunkPosition> chunks = List.of();
        int next, checked, skipped;
        String error;

        Search(Player player, World world, int x, int z) {
            this.player = player;
            playerId = player.getUniqueId();
            this.world = world;
            index = new RetroGenerationSearch(x, z);
        }
    }
}
