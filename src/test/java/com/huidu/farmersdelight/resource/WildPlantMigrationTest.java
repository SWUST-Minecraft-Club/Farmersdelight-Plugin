package com.huidu.farmersdelight.resource;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WildPlantMigrationTest {
    @TempDir Path directory;
    private static final List<String> PLANTS = List.of("wild_cabbages", "wild_onions", "wild_tomatoes");

    @Test
    void upgradesExistingDefaultPlantsWithAnExactBackupAndIsIdempotent() throws Exception {
        // Preserve CE's dotted version keys, event key "on", comments, and non-BMP Unicode verbatim.
        Path file = directory.resolve("wild_plants.yml");
        Files.writeString(file, "# 用户配置 🌱\n" + legacyText() + "\ncustom-setting: keep me\n");
        String original = Files.readString(file);
        assertEquals(1, ResourceInstaller.migrateWildPlantStates(file));
        YamlConfiguration migrated = ConfigFileUpdater.readYamlFile(file);
        for (String plant : PLANTS) assertEquals("higher_tripwire", migrated.getString(path(plant) + ".auto_state"));
        assertEquals("keep me", migrated.getString("custom-setting"));
        assertEquals(original.replace("auto_state: sugar_cane", "auto_state: higher_tripwire"), Files.readString(file));
        try (var files = Files.list(directory)) {
            List<Path> backups = files.filter(path -> path.toString().endsWith(".bak")).toList();
            assertEquals(1, backups.size());
            assertEquals(original, Files.readString(backups.getFirst()));
        }
        String after = Files.readString(file);
        assertEquals(0, ResourceInstaller.migrateWildPlantStates(file));
        assertEquals(after, Files.readString(file));
    }

    @Test
    void preservesCustomModelsAndExplicitStateAllocations() throws Exception {
        YamlConfiguration config = legacyConfig();
        config.set(path(PLANTS.get(0)) + ".models", List.of(Map.of("path", "my_pack:block/custom_cabbage")));
        config.set(path(PLANTS.get(1)) + ".auto_state", "lower_tripwire");
        config.set(path(PLANTS.get(2)) + ".auto_state", Map.of("type", "sugar_cane", "id", "custom:shared"));
        Path file = save(config);
        String original = Files.readString(file);
        assertEquals(0, ResourceInstaller.migrateWildPlantStates(file));
        assertEquals(original, Files.readString(file));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    void invalidYamlIsNotOverwritten() throws Exception {
        Path file = directory.resolve("wild_plants.yml");
        Files.writeString(file, "blocks: [invalid\n");
        assertThrows(java.io.IOException.class, () -> ResourceInstaller.migrateWildPlantStates(file));
        assertEquals("blocks: [invalid\n", Files.readString(file));
    }

    private YamlConfiguration legacyConfig() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(legacyText());
        return config;
    }

    private String legacyText() throws Exception {
        try (var stream = getClass().getResourceAsStream("/craftengine/farmersdelight/configuration/wild_plants.yml")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("auto_state: higher_tripwire", "auto_state: sugar_cane");
        }
    }

    private Path save(YamlConfiguration config) throws Exception {
        Path file = directory.resolve("wild_plants.yml");
        Files.writeString(file, config.saveToString());
        return file;
    }

    private String path(String plant) { return "blocks.farmersdelight:" + plant + ".state"; }
}
