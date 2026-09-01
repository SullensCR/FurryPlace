package com.furryplace.event.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceYamlTest {
    private static final List<String> MENUS = List.of(
        "start-event", "inactive-info", "complete", "main-player", "main-admin", "main-judge",
        "browser", "judge-browser", "review-start-browser", "winner-browser", "tools", "weather",
        "time", "biome", "review", "confirm"
    );

    @Test
    void everyBundledMenuIsReadableAndHasValidInventoryGeometry() {
        for (String menu : MENUS) {
            YamlConfiguration yaml = load("menus/" + menu + ".yml");
            assertEquals("1.0.1", yaml.getString("config-version"), menu);
            assertNotNull(yaml.getString("name"), menu);
            int size = yaml.getInt("size");
            assertTrue(size >= 9 && size <= 54 && size % 9 == 0, menu);
            assertNotNull(yaml.getConfigurationSection("items"), menu);
        }
    }

    @Test
    void primaryResourcesAreReadable() {
        assertEquals("1.0.1", load("config.yml").getString("config-version"));
        assertEquals("lobby", load("config.yml").getString("worlds.lobby"));
        assertEquals("1.0.1", load("messages.yml").getString("config-version"));
        assertNotNull(load("messages.yml").getString("winner.chat"));
        assertEquals("1.0.1", load("biome-names.yml").getString("config-version"));
        assertNotNull(load("biome-names.yml").getConfigurationSection("names.minecraft"));
        assertEquals("com.furryplace.event.FurryplaceEventPlugin", load("plugin.yml").getString("main"));
    }

    private YamlConfiguration load(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(input, path);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
}
