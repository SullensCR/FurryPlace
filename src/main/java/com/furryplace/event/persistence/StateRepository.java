package com.furryplace.event.persistence;

import com.furryplace.event.domain.EnvironmentSettings;
import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.domain.StoredLocation;
import com.furryplace.event.domain.WorldBlockKey;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StateRepository {
    private final Plugin plugin;
    private final OrderedDataWriter writer;
    private final Path eventFile;
    private final Path plotsFile;
    private final Path operationsFile;

    public StateRepository(Plugin plugin, OrderedDataWriter writer) {
        this.plugin = plugin;
        this.writer = writer;
        Path data = plugin.getDataFolder().toPath().resolve("data");
        eventFile = data.resolve("event.yml");
        plotsFile = data.resolve("plots.yml");
        operationsFile = data.resolve("operations.yml");
    }

    public RuntimeState load(int defaultMinutes) {
        RuntimeState state = new RuntimeState();
        state.configuredMinutes(defaultMinutes);
        loadEvent(state);
        loadPlots(state);
        loadOperation(state);
        return state;
    }

    public void save(RuntimeState state) {
        writer.submit(eventFile, serializeEvent(state));
        writer.submit(plotsFile, serializePlots(state));
        writer.submit(operationsFile, serializeOperation(state));
    }

    public void saveNow(RuntimeState state) throws Exception {
        AtomicFiles.writeString(eventFile, serializeEvent(state));
        AtomicFiles.writeString(plotsFile, serializePlots(state));
        AtomicFiles.writeString(operationsFile, serializeOperation(state));
    }

    private void loadEvent(RuntimeState state) {
        File file = eventFile.toFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try {
            state.stage(EventStage.valueOf(yaml.getString("stage", "INACTIVE")));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Estado persistido inválido; se usará INACTIVE.");
            state.stage(EventStage.INACTIVE);
        }
        state.configuredMinutes(yaml.getInt("configured-minutes", state.configuredMinutes()));
        state.deadlineEpochMillis(yaml.getLong("deadline-epoch-millis", 0L));
        state.templateInitialized(yaml.getBoolean("template-initialized", false));
        state.snapshotVersion(yaml.getLong("snapshot-version", 0L));
        String winner = yaml.getString("winner");
        if (winner != null && !winner.isBlank()) state.winner(UUID.fromString(winner));
        yaml.getStringList("pending-winner-notifications").stream().map(UUID::fromString).forEach(state.pendingWinnerNotifications()::add);
        ConfigurationSection spawn = yaml.getConfigurationSection("lobby-spawn");
        if (spawn != null) {
            state.lobbySpawn(new StoredLocation(spawn.getString("world", "lobby"), spawn.getDouble("x"), spawn.getDouble("y"), spawn.getDouble("z"), (float) spawn.getDouble("yaw"), (float) spawn.getDouble("pitch")));
        }
        for (Map<?, ?> entry : yaml.getMapList("portal-blocks")) {
            state.portalBlocks().add(new WorldBlockKey(String.valueOf(entry.get("world")), intValue(entry.get("x")), intValue(entry.get("y")), intValue(entry.get("z"))));
        }
        state.communityVotes().load(readVotes(yaml.getConfigurationSection("community-votes")));
        state.judgeVotes().load(readVotes(yaml.getConfigurationSection("judge-votes")));
        List<UUID> order = yaml.getStringList("review.order").stream().map(UUID::fromString).toList();
        Set<UUID> visited = new LinkedHashSet<>(yaml.getStringList("review.visited").stream().map(UUID::fromString).toList());
        String controller = yaml.getString("review.controller");
        state.review().restore(order, visited, yaml.getInt("review.current-index", 0), controller == null || controller.isBlank() ? null : UUID.fromString(controller), yaml.getBoolean("review.paused", false));
    }

    private void loadOperation(RuntimeState state) {
        File file = operationsFile.toFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        state.activeOperation(yaml.getString("active.type"), yaml.getString("active.payload"));
    }

    private void loadPlots(RuntimeState state) {
        File file = plotsFile.toFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection plots = yaml.getConfigurationSection("plots");
        if (plots == null) return;
        List<PlotRecord> records = new ArrayList<>();
        for (String key : plots.getKeys(false)) {
            ConfigurationSection section = plots.getConfigurationSection(key);
            if (section == null) continue;
            try {
                UUID owner = UUID.fromString(key);
                PlotRecord record = new PlotRecord(section.getInt("index"), owner, section.getString("name", owner.toString()), PlotRecord.Status.valueOf(section.getString("status", "RESERVED")), section.getLong("snapshot-version"), Instant.ofEpochMilli(section.getLong("reserved-at", System.currentTimeMillis())));
                String weather = section.getString("environment.weather");
                String time = section.getString("environment.time");
                String biome = section.getString("environment.biome");
                if (weather != null) record.environment().weather(EnvironmentSettings.WeatherChoice.valueOf(weather));
                if (time != null) record.environment().time(EnvironmentSettings.TimeChoice.valueOf(time));
                if (biome != null) record.environment().biome(NamespacedKey.fromString(biome));
                records.add(record);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Parcela persistida inválida " + key + ": " + exception.getMessage());
            }
        }
        state.restorePlots(records);
    }

    private String serializeEvent(RuntimeState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("stage", state.stage().name());
        yaml.set("configured-minutes", state.configuredMinutes());
        yaml.set("deadline-epoch-millis", state.deadlineEpochMillis());
        yaml.set("template-initialized", state.templateInitialized());
        yaml.set("snapshot-version", state.snapshotVersion());
        yaml.set("winner", state.winner() == null ? null : state.winner().toString());
        yaml.set("pending-winner-notifications", state.pendingWinnerNotifications().stream().map(UUID::toString).toList());
        if (state.lobbySpawn() != null) {
            StoredLocation spawn = state.lobbySpawn();
            yaml.set("lobby-spawn.world", spawn.world());
            yaml.set("lobby-spawn.x", spawn.x());
            yaml.set("lobby-spawn.y", spawn.y());
            yaml.set("lobby-spawn.z", spawn.z());
            yaml.set("lobby-spawn.yaw", spawn.yaw());
            yaml.set("lobby-spawn.pitch", spawn.pitch());
        }
        List<Map<String, Object>> portal = state.portalBlocks().stream().map(block -> {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("world", block.world()); map.put("x", block.x()); map.put("y", block.y()); map.put("z", block.z()); return map;
        }).toList();
        yaml.set("portal-blocks", portal);
        writeVotes(yaml, "community-votes", state.communityVotes().snapshot());
        writeVotes(yaml, "judge-votes", state.judgeVotes().snapshot());
        yaml.set("review.order", state.review().order().stream().map(UUID::toString).toList());
        yaml.set("review.visited", state.review().visited().stream().map(UUID::toString).toList());
        yaml.set("review.current-index", state.review().currentIndex());
        yaml.set("review.controller", state.review().controller() == null ? null : state.review().controller().toString());
        yaml.set("review.paused", state.review().paused());
        return yaml.saveToString();
    }

    private String serializeOperation(RuntimeState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("active.type", state.activeOperationType());
        yaml.set("active.payload", state.activeOperationPayload());
        return yaml.saveToString();
    }

    private String serializePlots(RuntimeState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlotRecord plot : state.plots()) {
            String root = "plots." + plot.ownerId();
            yaml.set(root + ".index", plot.index());
            yaml.set(root + ".name", plot.ownerName());
            yaml.set(root + ".status", plot.status().name());
            yaml.set(root + ".snapshot-version", plot.snapshotVersion());
            yaml.set(root + ".reserved-at", plot.reservedAt().toEpochMilli());
            yaml.set(root + ".environment.weather", plot.environment().weather() == null ? null : plot.environment().weather().name());
            yaml.set(root + ".environment.time", plot.environment().time() == null ? null : plot.environment().time().name());
            yaml.set(root + ".environment.biome", plot.environment().biome() == null ? null : plot.environment().biome().asString());
        }
        return yaml.saveToString();
    }

    private Map<UUID, UUID> readVotes(ConfigurationSection section) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        if (section == null) return result;
        for (String voter : section.getKeys(false)) {
            String selected = section.getString(voter);
            if (selected != null) result.put(UUID.fromString(voter), UUID.fromString(selected));
        }
        return result;
    }

    private void writeVotes(YamlConfiguration yaml, String root, Map<UUID, UUID> votes) {
        votes.forEach((voter, selected) -> yaml.set(root + "." + voter, selected.toString()));
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
