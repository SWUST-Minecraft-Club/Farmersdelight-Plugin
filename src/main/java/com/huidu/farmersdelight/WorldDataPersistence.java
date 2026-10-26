package com.huidu.farmersdelight;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntityController;
import com.huidu.farmersdelight.block.behavior.SkilletBlockEntityController;
import com.huidu.farmersdelight.block.behavior.StoveBlockEntityController;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Persists the plugin's per-world block state at shutdown and on world unload. The shutdown path, the
 * world-unload path and CraftEngine's own world flush all need the same three steps.
 */
final class WorldDataPersistence {

    private WorldDataPersistence() {
    }

    /** Called after region ticks stop, before CraftEngine closes storage or FD clears its managers. */
    static void prepareShutdown(FarmersDelightPlugin plugin) {
        for (World world : plugin.getServer().getWorlds()) {
            CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
            if (ceWorld == null) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                CEChunk ceChunk = ceWorld.getChunkAtIfLoaded(chunk.getX(), chunk.getZ());
                if (ceChunk == null) continue;
                for (BlockEntity entity : ceChunk.blockEntities()) {
                    plugin.runDisableStep("plugin.disable_step_save_block_data", () -> {
                        entity.controller.let(CookingPotBlockEntityController.class, CookingPotBlockEntityController::prepareForShutdown);
                        entity.controller.let(CuttingBoardBlockEntityController.class, CuttingBoardBlockEntityController::prepareForShutdown);
                        entity.controller.let(SkilletBlockEntityController.class, SkilletBlockEntityController::prepareForShutdown);
                        entity.controller.let(StoveBlockEntityController.class, StoveBlockEntityController::prepareForShutdown);
                    });
                }
            }
        }
        flushCraftEngineWorldData(plugin);
    }

    /**
     * Marks (Folia, where the write must happen on the owning region) or writes (Paper) every block entity's
     * data, then flushes CraftEngine's own per-world data.
     */
    static void saveAllBlockData(FarmersDelightPlugin plugin) {
        if (plugin.scheduler() != null && plugin.scheduler().isFolia()) {
            plugin.runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::markAllBlockEntitiesDirty);
            plugin.runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::markAllBlockEntitiesDirty);
        } else {
            plugin.runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::saveAllData);
            plugin.runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::saveAllData);
        }
        plugin.runDisableStep("plugin.disable_step_save_skillet_data", SkilletBlockBehavior::saveAllData);
        plugin.runDisableStep("plugin.disable_step_save_stove_data", StoveCookingBlockBehavior::saveAllData);

        flushCraftEngineWorldData(plugin);
    }

    /** Writes one world's block-entity data; called when that world unloads, not at plugin shutdown. */
    static void saveWorldBlockData(FarmersDelightPlugin plugin, World world) {
        if (world == null) {
            return;
        }
        for (BlockPosKey posKey : CookingPotBlockBehavior.getAllBlockEntities(world).keySet()) {
            CookingPotBlockBehavior.passivateBlockEntityData(world, posKey);
        }
        for (BlockPosKey posKey : CuttingBoardBlockBehavior.getAllBlockEntities(world).keySet()) {
            CuttingBoardBlockBehavior.passivateBlockEntityData(world, posKey);
        }
        if (plugin.getSkilletManager() != null) {
            plugin.getSkilletManager().saveWorldData(world);
        }
        if (plugin.getStoveManager() != null) {
            plugin.getStoveManager().saveWorldData(world);
        }
    }

    private static void flushCraftEngineWorldData(FarmersDelightPlugin plugin) {
        // The pre-shutdown event prepares snapshots before CE closes its world storage. A later
        // disable callback may run after CE is disabled; only flush while its storage is available.
        if (!plugin.getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            plugin.runDisableStep("plugin.disable_step_flush_craftengine_world", () -> {
                CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
                if (ceWorld != null) {
                    ceWorld.saveChunks();
                    ceWorld.saveSettings();
                    try {
                        ceWorld.worldDataStorage().flush();
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
            }, "world", world.getName());
        }
    }
}
