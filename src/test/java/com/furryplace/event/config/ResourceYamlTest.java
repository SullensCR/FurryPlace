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
            assertEquals("1.0.9", yaml.getString("config-version"), menu);
            assertNotNull(yaml.getString("name"), menu);
            int size = yaml.getInt("size");
            assertTrue(size >= 9 && size <= 54 && size % 9 == 0, menu);
            assertNotNull(yaml.getConfigurationSection("items"), menu);
        }
    }

    @Test
    void primaryResourcesAreReadable() {
        assertEquals("1.0.9", load("config.yml").getString("config-version"));
        assertEquals("lobby", load("config.yml").getString("worlds.lobby"));
        assertEquals("1.0.9", load("messages.yml").getString("config-version"));
        assertEquals("<yellow><b>Menu de FurryPlace</b></yellow>", load("messages.yml").getString("items.menu-name"));
        assertEquals(java.util.List.of("", "<dark_gray>Click para abrir el menu del evento!</dark_gray>"),
            load("messages.yml").getStringList("items.menu-lore"));
        assertNotNull(load("messages.yml").getString("winner.chat"));
        assertTrue(load("messages.yml").getString("review.actionbar").contains("<speed-slow>"));
        assertEquals("Velocidad", load("messages.yml").getString("review.speed-name").replaceAll("<[^>]+>", ""));
        assertEquals(0.05D, load("config.yml").getDouble("review.speed-slow"));
        assertEquals(0.10D, load("config.yml").getDouble("review.speed-normal"));
        assertEquals(0.20D, load("config.yml").getDouble("review.speed-fast"));
        assertEquals("SAVE_TEMPLATE", load("menus/main-admin.yml").getString("items.save-template.action"));
        assertEquals("REVIEW_START", load("menus/start-event.yml").getString("items.review-start.action"));
        assertEquals(21, load("menus/start-event.yml").getInt("items.review-start.slot"));
        assertEquals("1.0.9", load("biome-names.yml").getString("config-version"));
        assertNotNull(load("biome-names.yml").getConfigurationSection("names.minecraft"));
        assertEquals("com.furryplace.event.FurryplaceEventPlugin", load("plugin.yml").getString("main"));
    }

    private YamlConfiguration load(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(input, path);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
}
