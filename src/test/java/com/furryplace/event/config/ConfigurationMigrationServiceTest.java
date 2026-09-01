package com.furryplace.event.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationMigrationServiceTest {
    @TempDir
    Path directory;

    @Test
    void upgradeBacksUpTheOldFileAndMergesItsValuesIntoTheNewDefault() throws Exception {
        Path config = directory.resolve("config.yml");
        String existing = """
            worlds:
              lobby: custom-lobby
            timer:
              default-minutes: 45
            retired-option: preserved
            """;
        Files.writeString(config, existing);
        String defaults = """
            config-version: '1.0.1'
            worlds:
              lobby: lobby
              plots: place
            timer:
              default-minutes: 20
              maximum-minutes: 180
            fresh-option: added
            """;

        ConfigurationMigrationService.MigrationResult result = ConfigurationMigrationService.migrate(directory,
            Map.of("config.yml", defaults));

        assertEquals(java.util.List.of("config.yml"), result.updated());
        assertNotNull(result.backupDirectory());
        assertEquals(existing, Files.readString(result.backupDirectory().resolve("config.yml")));

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(config.toFile());
        assertEquals("1.0.1", migrated.getString("config-version"));
        assertEquals("custom-lobby", migrated.getString("worlds.lobby"));
        assertEquals("place", migrated.getString("worlds.plots"));
        assertEquals(45, migrated.getInt("timer.default-minutes"));
        assertEquals(180, migrated.getInt("timer.maximum-minutes"));
        assertEquals("added", migrated.getString("fresh-option"));
        assertEquals("preserved", migrated.getString("retired-option"));
    }

    @Test
    void currentOrNewerFilesAreNotRewritten() throws Exception {
        Path config = directory.resolve("config.yml");
        String existing = "config-version: '1.2.0'\nvalue: custom\n";
        Files.writeString(config, existing);

        ConfigurationMigrationService.MigrationResult result = ConfigurationMigrationService.migrate(directory,
            Map.of("config.yml", "config-version: '1.1.9'\nvalue: default\nnew-value: added\n"));

        assertTrue(result.updated().isEmpty());
        assertNull(result.backupDirectory());
        assertEquals(existing, Files.readString(config));
        assertFalse(Files.exists(directory.resolve("backups")));
    }
}
