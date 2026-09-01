package com.furryplace.event.config;

import com.furryplace.event.persistence.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Updates editable bundled YAML files without discarding administrator changes. */
public final class ConfigurationMigrationService {
    public static final List<String> MENU_NAMES = List.of(
        "start-event", "inactive-info", "complete", "main-player", "main-admin", "main-judge",
        "browser", "judge-browser", "review-start-browser", "winner-browser", "tools", "weather", "time",
        "biome", "review", "confirm"
    );

    private static final String VERSION_KEY = "config-version";
    private static final List<String> RESOURCES = resources();
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss_SSS")
        .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;

    public ConfigurationMigrationService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate() throws IOException {
        Map<String, String> bundled = new LinkedHashMap<>();
        for (String resource : RESOURCES) bundled.put(resource, bundledResource(resource));
        MigrationResult result = migrate(plugin.getDataFolder().toPath(), bundled);
        if (!result.updated().isEmpty()) {
            plugin.getLogger().info("Configuraciones actualizadas a una nueva versión. Copias previas: "
                + plugin.getDataFolder().toPath().relativize(result.backupDirectory()) + "; archivos: "
                + String.join(", ", result.updated()));
        }
    }

    static MigrationResult migrate(Path dataDirectory, Map<String, String> bundled) throws IOException {
        Path backupDirectory = null;
        List<String> updated = new ArrayList<>();
        for (Map.Entry<String, String> entry : bundled.entrySet()) {
            String resource = entry.getKey();
            Path target = dataDirectory.resolve(resource);
            if (!Files.isRegularFile(target)) {
                AtomicFiles.writeString(target, entry.getValue());
                continue;
            }

            YamlConfiguration current = load(Files.readString(target, StandardCharsets.UTF_8));
            YamlConfiguration defaults = load(entry.getValue());
            if (compareVersions(current.getString(VERSION_KEY, "0"), defaults.getString(VERSION_KEY, "0")) >= 0) continue;

            if (backupDirectory == null) backupDirectory = uniqueBackupDirectory(dataDirectory.resolve("backups"));
            Path backup = backupDirectory.resolve(resource);
            Files.createDirectories(backup.getParent());
            Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);

            merge(defaults, current);
            defaults.set(VERSION_KEY, defaults.getString(VERSION_KEY, "0"));
            AtomicFiles.writeString(target, defaults.saveToString());
            updated.add(resource);
        }
        return new MigrationResult(backupDirectory, List.copyOf(updated));
    }

    private String bundledResource(String resource) throws IOException {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) throw new IOException("Falta el recurso incluido " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> resources() {
        List<String> resources = new ArrayList<>(List.of("config.yml", "messages.yml", "biome-names.yml"));
        MENU_NAMES.forEach(name -> resources.add("menus/" + name + ".yml"));
        return List.copyOf(resources);
    }

    private static YamlConfiguration load(String contents) {
        return YamlConfiguration.loadConfiguration(new StringReader(contents));
    }

    private static void merge(ConfigurationSection defaults, ConfigurationSection current) {
        for (String key : current.getKeys(false)) {
            if (VERSION_KEY.equals(key)) continue;
            Object currentValue = current.get(key);
            ConfigurationSection currentSection = current.getConfigurationSection(key);
            ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
            if (currentSection != null && defaultSection != null) merge(defaultSection, currentSection);
            else defaults.set(key, currentValue);
        }
    }

    private static Path uniqueBackupDirectory(Path backups) throws IOException {
        String base = BACKUP_TIME.format(Instant.now());
        Path candidate = backups.resolve(base);
        int suffix = 2;
        while (Files.exists(candidate)) candidate = backups.resolve(base + "-" + suffix++);
        Files.createDirectories(candidate);
        return candidate;
    }

    private static int compareVersions(String left, String right) {
        int[] leftParts = versionParts(left);
        int[] rightParts = versionParts(right);
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? leftParts[index] : 0;
            int rightPart = index < rightParts.length ? rightParts[index] : 0;
            if (leftPart != rightPart) return Integer.compare(leftPart, rightPart);
        }
        return 0;
    }

    private static int[] versionParts(String version) {
        String core = version == null ? "0" : version.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        String[] raw = core.split("\\.");
        int[] parts = new int[raw.length];
        for (int index = 0; index < raw.length; index++) {
            try {
                parts[index] = Integer.parseInt(raw[index]);
            } catch (NumberFormatException ignored) {
                parts[index] = 0;
            }
        }
        return parts;
    }

    record MigrationResult(Path backupDirectory, List<String> updated) {}
}
