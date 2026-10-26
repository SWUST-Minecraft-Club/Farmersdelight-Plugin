package com.huidu.farmersdelight;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.worldgen.RetroGenerationEngine;
import com.huidu.farmersdelight.worldgen.RetroGenerationProtection;
import com.huidu.farmersdelight.worldgen.RetroGenerationPlacements;
import com.huidu.farmersdelight.worldgen.RetroGenerationRule;
import com.huidu.farmersdelight.worldgen.RetroGenerationSettings;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Optional, bounded backfill of loaded old chunks. All terrain and PDC access stays on the owner region. */
public final class RetroGenerationService implements Listener {
    private static final int QUEUE_LIMIT = 2048;
    private static final int MAX_RETRIES = 60;
    private static final long RETRY_DELAY = TimeUnit.SECONDS.toNanos(1);
    private static final Set<String> SINGLE_BLOCK_BEHAVIORS = Set.of(
            "net.momirealms.craftengine.bukkit.block.behavior.LiquidFlowableBlockBehavior",
            "net.momirealms.craftengine.bukkit.block.behavior.BushBlockBehavior",
            "com.huidu.farmersdelight.block.behavior.WildPlantBlockBehavior");
    private final FarmersDelightPlugin plugin;
    private final NamespacedKey marker;
    private final CraftEngineResourceLease resourceLease;
    private final RetroGenerationLocator locator;
    private final Queue<Ticket> queue = new ConcurrentLinkedQueue<>();
    private final Map<ChunkKey, Ticket> pending = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final LongAdder scanned = new LongAdder();
    private final LongAdder placed = new LongAdder();
    private final LongAdder empty = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final Map<RetroGenerationEngine.Rejection, LongAdder> rejections = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot;
    private volatile RetroGenerationProtection protection;
    private volatile boolean stopped;
    private PluginTask dispatcher;
    private int refillTicks;
    private int refillOffset;
    private volatile int usableRules = -1;

    RetroGenerationService(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "retro_generation_v1");
        resourceLease = new CraftEngineResourceLease(plugin.getCraftEngine(), plugin.getLogger());
        locator = new RetroGenerationLocator(plugin, resourceLease, this::ready);
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
        dispatcher = plugin.scheduler().runRepeating(this::drain, 20L, 1L);
    }

    void reload() {
        snapshot = null;
        usableRules = -1;
        long revision = generation.incrementAndGet();
        queue.clear();
        pending.clear();
        protection = null;
        failureReported.set(false);
        try {
            World primary = plugin.getPrimaryWorld();
            RetroGenerationSettings settings = RetroGenerationSettings.load(plugin.getConfig(),
                    primary == null ? "world" : primary.getName());
            Path root = plugin.getCraftEngine().javaPlugin().getDataFolder().toPath()
                    .resolve("resources/farmersdelight/configuration");
            List<RetroGenerationRule> rules = RetroGenerationRule.load(root, plugin.getServer().getMinecraftVersion());
            snapshot = new Snapshot(revision, settings, rules);
            if (settings.enabled()) {
                if (rules.isEmpty()) {
                    I18n.logWarning("retrogen.no_rules");
                    return;
                }
                I18n.logInfo("retrogen.started", "worlds", String.join(", ", settings.worlds()), "rules", rules.size());
                for (World world : plugin.getServer().getWorlds()) {
                    if (!eligibleWorld(world, settings)) continue;
                    for (Chunk chunk : world.getLoadedChunks()) enqueue(world, chunk.getX(), chunk.getZ());
                }
            }
        } catch (Exception failure) {
            snapshot = null;
            I18n.logWarning("retrogen.failed", "error", String.valueOf(failure.getMessage()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Snapshot current = snapshot;
        if (stopped || current == null || !current.settings().enabled()
                || !eligibleWorld(event.getWorld(), current.settings())) return;
        if (event.isNewChunk()) {
            // New chunks use CE's normal world generation and must not get a second pass on a later load.
            event.getChunk().getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        } else enqueue(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        locator.cancelWorld(world);
        pending.keySet().removeIf(key -> key.world().equals(world));
        queue.removeIf(ticket -> ticket.key().world().equals(world));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) { locator.cancelPlayer(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) { invalidateProtection(); }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) { invalidateProtection(); }

    private synchronized void invalidateProtection() { protection = null; }

    private void enqueue(World world, int x, int z) {
        Snapshot current = snapshot;
        if (current == null || stopped || pending.size() >= QUEUE_LIMIT) return;
        ChunkKey key = new ChunkKey(world.getUID(), x, z);
        Ticket ticket = new Ticket(key, world, current.revision(), 0, 0L);
        if (pending.putIfAbsent(key, ticket) == null) queue.add(ticket);
    }

    private void drain() {
        locator.tick();
        Snapshot current = snapshot;
        if (stopped || current == null || !current.settings().enabled() || !ready() || current.rules().isEmpty()) return;
        RetroGenerationProtection guard = protection(current);
        if (!guard.healthy()) return;
        // Recover chunks missed during a queue burst or temporary CE unavailability, without loading terrain.
        if (++refillTicks >= 1200) {
            refillTicks = 0;
            List<Chunk> chunks = new ArrayList<>();
            for (World world : plugin.getServer().getWorlds()) {
                if (!eligibleWorld(world, current.settings())) continue;
                for (Chunk chunk : world.getLoadedChunks()) chunks.add(chunk);
            }
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(Math.floorMod((long) refillOffset + i, chunks.size()));
                enqueue(chunk.getWorld(), chunk.getX(), chunk.getZ());
            }
            // Rotate across all worlds so > QUEUE_LIMIT loaded chunks cannot starve the tail.
            refillOffset = (refillOffset + QUEUE_LIMIT) & Integer.MAX_VALUE;
        }
        for (int i = 0; i < current.settings().chunksPerTick(); i++) {
            Ticket ticket = queue.poll();
            if (ticket == null) return;
            if (ticket.revision() != current.revision()) {
                pending.remove(ticket.key(), ticket);
                continue;
            }
            if (pending.get(ticket.key()) != ticket) continue;
            if (ticket.attempt() > 0 && System.nanoTime() - ticket.notBefore() < 0) {
                queue.add(ticket);
                continue;
            }
            try {
                plugin.scheduler().runAt(ticket.world(), ticket.key().x(), ticket.key().z(),
                        () -> process(ticket, current, guard));
            } catch (RuntimeException failure) {
                pending.remove(ticket.key(), ticket);
                reportFailure(failure);
            }
        }
    }

    private void process(Ticket ticket, Snapshot current, RetroGenerationProtection guard) {
        boolean retry = false;
        try {
            if (snapshot != current || stopped || pending.get(ticket.key()) != ticket
                    || !ticket.world().isChunkLoaded(ticket.key().x(), ticket.key().z())) return;
            try (AutoCloseable lease = resourceLease.tryAcquire()) {
                if (lease == null || !ready()) { retry = true; return; }
                // A protection plugin may have been enabled or disabled since dispatch.
                if (protection != guard || !guard.healthy()) { retry = true; return; }
                Chunk chunk = ticket.world().getChunkAt(ticket.key().x(), ticket.key().z());
                BukkitTerrain terrain = terrain(chunk, current, guard);
                if (terrain == null) { retry = true; return; }
                List<RetroGenerationRule> rules = loadedRules(current);
                if (rules.isEmpty()) return;
                RetroGenerationEngine.Plan plan = RetroGenerationEngine.plan(terrain, current.settings(), rules);
                if (!guard.healthy()) return;
                int additions = RetroGenerationEngine.commit(terrain, current.settings(), plan);
                if ("ready".equals(plan.reason())) {
                    scanned.increment();
                    if (additions == 0) empty.increment();
                    refused.add(plan.candidates().size() - additions);
                    plan.rejected().forEach((reason, count) ->
                            rejections.computeIfAbsent(reason, key -> new LongAdder()).add(count));
                }
                placed.add(additions);
            }
        } catch (Exception | LinkageError failure) {
            reportFailure(failure);
        } finally {
            if (retry && !stopped && snapshot == current && ticket.attempt() < MAX_RETRIES) {
                Ticket next = new Ticket(ticket.key(), ticket.world(), ticket.revision(), ticket.attempt() + 1,
                        System.nanoTime() + RETRY_DELAY);
                if (pending.replace(ticket.key(), ticket, next)) queue.add(next);
            } else pending.remove(ticket.key(), ticket);
        }
    }

    private void reportFailure(Throwable failure) {
        if (failureReported.compareAndSet(false, true)) {
            I18n.logWarning("retrogen.failed", "error", String.valueOf(failure.getMessage()));
        }
    }

    private boolean ready() {
        var engine = plugin.getCraftEngine();
        return engine != null && !engine.isEnabling() && !engine.isReloading() && !stopped && plugin.isEnabled();
    }

    private List<RetroGenerationRule> loadedRules(Snapshot current) {
        List<RetroGenerationRule> rules = current.rules().stream().filter(rule -> safeDefinition(rule.id())).toList();
        usableRules = rules.size();
        return rules;
    }

    private static boolean safeDefinition(String id) {
        var definition = CraftEngineBlocks.byId(Key.of(id));
        if (definition == null) return false;
        // User-edited wild-plant IDs may now represent a container, a tall block or arbitrary behaviour.
        // Do not call those onPlace hooks while backfilling an existing world.
        AtomicBoolean safe = new AtomicBoolean(true);
        AtomicBoolean found = new AtomicBoolean();
        definition.defaultState().behavior().let(BlockBehavior.class, behavior -> {
            found.set(true);
            if (!SINGLE_BLOCK_BEHAVIORS.contains(behavior.getClass().getName())) safe.set(false);
        });
        return found.get() && safe.get();
    }

    private synchronized RetroGenerationProtection protection(Snapshot current) {
        if (protection == null) {
            protection = RetroGenerationProtection.discover(plugin, current.settings().extraProtectionPlugins(),
                    error -> I18n.logWarning("retrogen.protection_error", "error", error));
            if (!protection.blocked().isEmpty()) {
                I18n.logWarning("retrogen.protection_blocked", "plugins", String.join(", ", protection.blocked()));
            }
        }
        return protection;
    }

    private static boolean eligibleWorld(World world, RetroGenerationSettings settings) {
        return world.getEnvironment() == World.Environment.NORMAL && settings.worlds().contains(world.getName());
    }

    public Map<String, String> status() {
        Snapshot current = snapshot;
        RetroGenerationProtection guard = protection;
        return Map.of("enabled", String.valueOf(current != null && current.settings().enabled()),
                "worlds", current == null ? "-" : String.join(", ", current.settings().worlds()),
                "rules", String.valueOf(current == null ? 0 : current.rules().size()),
                "usable", usableRules < 0 ? "?" : String.valueOf(usableRules), "engine", ready() ? "ready" : "waiting",
                "queued", String.valueOf(pending.size()), "scanned", String.valueOf(scanned.sum()),
                "placed", String.valueOf(placed.sum()),
                "blocked", guard == null ? "-" : !guard.blocked().isEmpty() ? String.join(", ", guard.blocked())
                        : guard.healthy() ? "-" : "claim query failed; reload required");
    }

    public Map<String, String> diagnostics() {
        Snapshot current = snapshot;
        Map<RetroGenerationEngine.Rejection, Long> counts = new java.util.EnumMap<>(RetroGenerationEngine.Rejection.class);
        rejections.forEach((reason, count) -> counts.put(reason, count.sum()));
        return Map.of("density", current == null ? "-" : String.valueOf(current.settings().densityMultiplier()),
                "empty", String.valueOf(empty.sum()), "refused", String.valueOf(refused.sum()),
                "rejected", counts.isEmpty() ? "-" : counts.toString());
    }

    /** Finds actual saved plants, including those placed before this server session. */
    public void locate(Player player) { locator.start(player); }

    /** Read-only preview of the player's current chunk; called on that player's owning region. */
    public void preview(Player player) {
        Snapshot current = snapshot;
        if (current == null || !ready() || !eligibleWorld(player.getWorld(), current.settings())) {
            player.sendMessage(I18n.getComponent("retrogen.preview_unavailable", player));
            return;
        }
        player.sendMessage(I18n.getComponent("retrogen.preview_context", player, Map.of(
                "world", player.getWorld().getName(), "biome", player.getLocation().getBlock().getBiome().getKey().toString(),
                "density", String.valueOf(current.settings().densityMultiplier()))));
        try (AutoCloseable lease = resourceLease.tryAcquire()) {
            if (lease == null) {
                player.sendMessage(I18n.getComponent("retrogen.preview_unavailable", player));
                return;
            }
            RetroGenerationProtection guard = protection(current);
            if (!guard.healthy()) {
                player.sendMessage(I18n.getComponent("retrogen.protection_blocked", player,
                        Map.of("plugins", status().get("blocked"))));
                return;
            }
            Chunk chunk = player.getLocation().getChunk();
            BukkitTerrain terrain = terrain(chunk, current, guard);
            List<RetroGenerationRule> rules = loadedRules(current);
            if (terrain == null || rules.isEmpty()) {
                player.sendMessage(I18n.getComponent("retrogen.preview_unavailable", player));
                return;
            }
            var plan = RetroGenerationEngine.plan(terrain, current.settings(), rules);
            if ("processed".equals(plan.reason())) {
                player.sendMessage(I18n.getComponent("retrogen.preview_processed", player,
                        Map.of("x", String.valueOf(chunk.getX()), "z", String.valueOf(chunk.getZ()))));
                return;
            }
            player.sendMessage(I18n.getComponent("retrogen.preview", player, Map.of("x", String.valueOf(chunk.getX()),
                    "z", String.valueOf(chunk.getZ()), "count", String.valueOf(plan.candidates().size()),
                    "reason", plan.reason(), "rejected", plan.rejected().toString())));
            for (var candidate : plan.candidates()) {
                player.sendMessage(I18n.getComponent("retrogen.preview_candidate", player, Map.of(
                        "id", candidate.rule().id(), "x", String.valueOf((chunk.getX() << 4) + candidate.x()),
                        "y", String.valueOf(candidate.y()), "z", String.valueOf((chunk.getZ() << 4) + candidate.z()))));
            }
        } catch (Exception | LinkageError failure) {
            player.sendMessage(I18n.getComponent("retrogen.failed", player,
                    Map.of("error", String.valueOf(failure.getMessage()))));
        }
    }

    private BukkitTerrain terrain(Chunk chunk, Snapshot current, RetroGenerationProtection guard) {
        CEWorld world = CustomBlockUtils.getCEWorld(chunk.getWorld());
        if (world == null || world.getChunkAtIfLoaded(chunk.getX(), chunk.getZ()) == null) return null;
        return new BukkitTerrain(chunk, world, current, guard);
    }

    void stop() {
        stopped = true;
        locator.stop();
        generation.incrementAndGet();
        if (dispatcher != null) dispatcher.cancel();
        queue.clear();
        pending.clear();
    }

    private record Snapshot(long revision, RetroGenerationSettings settings, List<RetroGenerationRule> rules) { }
    private record ChunkKey(UUID world, int x, int z) { }
    private record Ticket(ChunkKey key, World world, long revision, int attempt, long notBefore) { }

    private final class BukkitTerrain implements RetroGenerationEngine.Terrain {
        private final Chunk chunk;
        private final World world;
        private final CEWorld ceWorld;
        private final Snapshot current;
        private final RetroGenerationProtection guard;
        private final Location spawn;
        private final Map<RetroGenerationPlacements.Position, ImmutableBlockState> freshPlants = new HashMap<>();

        private BukkitTerrain(Chunk chunk, CEWorld ceWorld, Snapshot current, RetroGenerationProtection guard) {
            this.chunk = chunk;
            world = chunk.getWorld();
            this.ceWorld = ceWorld;
            this.current = current;
            this.guard = guard;
            spawn = world.getSpawnLocation();
        }

        private Block block(int x, int y, int z) {
            return world.getBlockAt((chunkX() << 4) + x, y, (chunkZ() << 4) + z);
        }
        @Override public int chunkX() { return chunk.getX(); }
        @Override public int chunkZ() { return chunk.getZ(); }
        @Override public int minHeight() { return world.getMinHeight(); }
        @Override public int maxHeight() { return world.getMaxHeight(); }
        @Override public long worldSeed() { return world.getSeed(); }
        @Override public String worldName() { return world.getName(); }
        @Override public int spawnX() { return spawn.getBlockX(); }
        @Override public int spawnZ() { return spawn.getBlockZ(); }
        @Override public int surfaceY(int x, int z) {
            return world.getHighestBlockYAt((chunkX() << 4) + x, (chunkZ() << 4) + z, HeightMap.MOTION_BLOCKING);
        }
        @Override public RetroGenerationPlacements.Result placements(RetroGenerationSettings settings, RetroGenerationRule rule) {
            return RetroGenerationNativePlacements.sample(world, this, settings, rule);
        }
        @Override public Material type(int x, int y, int z) { return block(x, y, z).getType(); }
        @Override public boolean readable(int x, int z) {
            if (x >= 0 && x < 16 && z >= 0 && z < 16) return true;
            int blockX = (chunkX() << 4) + x, blockZ = (chunkZ() << 4) + z;
            return world.isChunkLoaded(blockX >> 4, blockZ >> 4)
                    && ceWorld.getChunkAtIfLoaded(blockX >> 4, blockZ >> 4) != null
                    && plugin.scheduler().isOwnedByCurrentRegion(new Location(world, blockX, 0, blockZ));
        }
        @Override public boolean custom(int x, int y, int z) {
            var saved = ceWorld.getBlockStateAtIfLoaded((chunkX() << 4) + x, y, (chunkZ() << 4) + z);
            return (saved != null && !saved.isEmpty()) || CraftEngineBlocks.isCustomBlock(block(x, y, z));
        }
        @Override public boolean freshPlant(int x, int y, int z) {
            var expected = freshPlants.get(new RetroGenerationPlacements.Position(x, y, z));
            return expected != null && expected.equals(ceWorld.getBlockStateAtIfLoaded((chunkX() << 4) + x, y, (chunkZ() << 4) + z))
                    && CraftEngineBlocks.getBukkitBlockData(expected).equals(block(x, y, z).getBlockData());
        }
        @Override public String biome(int x, int y, int z) { return block(x, y, z).getBiome().getKey().toString(); }
        @Override public boolean skyExposed(int x, int y, int z) { return block(x, y, z).getLightFromSky() == 15; }
        @Override public boolean unclaimed(int x, int y, int z) { return guard.unclaimed(block(x, y, z).getLocation()); }
        @Override public boolean hasBlockEntities() {
            return chunk.getTileEntities(false).length != 0
                    || ceWorld.getChunkAtIfLoaded(chunkX(), chunkZ()) == null
                    || !ceWorld.getChunkAtIfLoaded(chunkX(), chunkZ()).blockEntities().isEmpty();
        }
        @Override public boolean processed() { return chunk.getPersistentDataContainer().has(marker, PersistentDataType.BYTE); }
        @Override public void markProcessed() { chunk.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1); }
        @Override public boolean active() { return !stopped && snapshot == current && ready() && protection == guard && guard.healthy(); }
        @Override public boolean place(RetroGenerationEngine.Candidate candidate) {
            if (!active()) return false;
            var definition = CraftEngineBlocks.byId(Key.of(candidate.rule().id()));
            if (definition == null || !safeDefinition(candidate.rule().id())) return false;
            var state = definition.defaultState();
            Block target = block(candidate.x(), candidate.y(), candidate.z());
            var expected = CraftEngineBlocks.getBukkitBlockData(state);
            var proposed = target.getState(false);
            proposed.setBlockData(expected);
            BlockFormEvent event = new BlockFormEvent(target, proposed);
            plugin.getServer().getPluginManager().callEvent(event);
            // Event listeners may change the target, its ground, a claim, or the proposed output.
            if (event.isCancelled() || !expected.equals(event.getNewState().getBlockData()) || !active()
                    || RetroGenerationEngine.rejection(this, current.settings(), candidate) != null) return false;
            // Known single-height wild plants only; no shape/neighbour updates, sound, drops or terrain generation.
            boolean success = CraftEngineBlocks.place(target.getLocation(), state, UpdateFlags.UPDATE_NO_PHYS, false, true);
            if (success) freshPlants.put(new RetroGenerationPlacements.Position(candidate.x(), candidate.y(), candidate.z()), state);
            return success;
        }
    }
}
