package com.furryplace.event.player;

import com.furryplace.event.persistence.AtomicFiles;
import com.furryplace.event.world.PlotService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Crash-recoverable inventory transactions for owners and temporary review state for viewers. */
public final class PlayerStateService implements PlotService.EntryHandler {
    private enum Mode { NORMAL, EVENT }
    private enum Marker { NONE, APPLY_EVENT, RESTORE_NORMAL }

    private static final class Data {
        private Mode mode = Mode.NORMAL;
        private Marker marker = Marker.NONE;
        private PlayerStateSnapshot normal;
        private PlayerStateSnapshot event;
    }

    private record ViewState(GameMode gameMode, boolean allowFlight, boolean flying, float flySpeed) {}

    private final JavaPlugin plugin;
    private final Path directory;
    private final Map<UUID, Data> cache = new HashMap<>();
    private final Map<UUID, ViewState> viewStates = new HashMap<>();

    public PlayerStateService(JavaPlugin plugin) {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("data").resolve("players");
    }

    @Override
    public void enterOwner(Player player, com.furryplace.event.domain.PlotRecord plot, Location arrival,
                           boolean flightFallback) {
        player.teleportAsync(arrival).thenAccept(success -> {
            if (!success) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                activateOwner(player);
                if (flightFallback) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            });
        });
    }

    @Override
    public void enterViewer(Player player, com.furryplace.event.domain.PlotRecord plot, Location arrival,
                            boolean flightFallback) {
        player.teleportAsync(arrival).thenAccept(success -> {
            if (success) plugin.getServer().getScheduler().runTask(plugin, () -> activateViewer(player));
        });
    }

    public void activateOwner(Player player) {
        Data data = data(player.getUniqueId());
        if (data.mode == Mode.EVENT) return;
        viewStates.remove(player.getUniqueId());
        data.normal = PlayerStateSnapshot.capture(player);
        data.marker = Marker.APPLY_EVENT;
        saveNow(player.getUniqueId(), data);
        if (data.event == null) data.event = PlayerStateSnapshot.emptyEvent(player);
        data.event.apply(player);
        data.mode = Mode.EVENT;
        data.marker = Marker.NONE;
        saveNow(player.getUniqueId(), data);
    }

    public void activateViewer(Player player) {
        leaveOwnerState(player);
        viewStates.putIfAbsent(player.getUniqueId(), new ViewState(player.getGameMode(), player.getAllowFlight(), player.isFlying(), player.getFlySpeed()));
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    public void leavePlace(Player player) {
        leaveOwnerState(player);
        ViewState viewing = viewStates.remove(player.getUniqueId());
        if (viewing != null) {
            player.setGameMode(viewing.gameMode());
            player.setAllowFlight(viewing.allowFlight());
            player.setFlying(viewing.flying() && viewing.allowFlight());
            player.setFlySpeed(viewing.flySpeed());
        }
        player.resetPlayerWeather();
        player.resetPlayerTime();
    }

    public void recoverOnJoin(Player player) {
        Data data = data(player.getUniqueId());
        if (data.normal != null && (data.mode == Mode.EVENT || data.marker != Marker.NONE)) {
            data.normal.apply(player);
            data.mode = Mode.NORMAL;
            data.marker = Marker.NONE;
            saveNow(player.getUniqueId(), data);
        }
    }

    public void onQuit(Player player) {
        Data data = data(player.getUniqueId());
        if (data.mode == Mode.EVENT) {
            data.event = PlayerStateSnapshot.capture(player);
            data.marker = Marker.RESTORE_NORMAL;
            saveNow(player.getUniqueId(), data);
        }
        ViewState viewing = viewStates.remove(player.getUniqueId());
        if (viewing != null) restoreViewingState(player, viewing);
    }

    public void shutdown(Iterable<? extends Player> players) {
        for (Player player : players) {
            onQuit(player);
            leaveOwnerState(player);
        }
    }

    public boolean isInEventState(Player player) {
        return data(player.getUniqueId()).mode == Mode.EVENT;
    }

    public boolean isViewing(Player player) {
        return viewStates.containsKey(player.getUniqueId());
    }

    public void clearEventData() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                    UUID uuid;
                    try { uuid = UUID.fromString(file.getFileName().toString().replace(".yml", "")); }
                    catch (IllegalArgumentException exception) { continue; }
                    Data data = data(uuid);
                    data.event = null;
                    data.mode = Mode.NORMAL;
                    data.marker = Marker.NONE;
                    saveNow(uuid, data);
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("No se pudieron limpiar los inventarios del evento: " + exception.getMessage());
        }
    }

    private void leaveOwnerState(Player player) {
        Data data = data(player.getUniqueId());
        if (data.mode != Mode.EVENT) return;
        data.event = PlayerStateSnapshot.capture(player);
        data.marker = Marker.RESTORE_NORMAL;
        saveNow(player.getUniqueId(), data);
        if (data.normal != null) data.normal.apply(player);
        data.mode = Mode.NORMAL;
        data.marker = Marker.NONE;
        saveNow(player.getUniqueId(), data);
    }

    private void restoreViewingState(Player player, ViewState viewing) {
        player.setGameMode(viewing.gameMode());
        player.setAllowFlight(viewing.allowFlight());
        player.setFlying(viewing.flying() && viewing.allowFlight());
        player.setFlySpeed(viewing.flySpeed());
    }

    private Data data(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    private Data load(UUID uuid) {
        Data data = new Data();
        Path file = path(uuid);
        if (!Files.isRegularFile(file)) return data;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        try { data.mode = Mode.valueOf(yaml.getString("mode", "NORMAL")); }
        catch (IllegalArgumentException ignored) { data.mode = Mode.NORMAL; }
        try { data.marker = Marker.valueOf(yaml.getString("transaction", "NONE")); }
        catch (IllegalArgumentException ignored) { data.marker = Marker.RESTORE_NORMAL; }
        ConfigurationSection normal = yaml.getConfigurationSection("normal");
        ConfigurationSection event = yaml.getConfigurationSection("event");
        if (normal != null) data.normal = PlayerStateSnapshot.read(normal);
        if (event != null) data.event = PlayerStateSnapshot.read(event);
        return data;
    }

    private void saveNow(UUID uuid, Data data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("mode", data.mode.name());
        yaml.set("transaction", data.marker.name());
        if (data.normal != null) data.normal.write(yaml.createSection("normal"));
        if (data.event != null) data.event.write(yaml.createSection("event"));
        try {
            AtomicFiles.writeString(path(uuid), yaml.saveToString());
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo guardar el inventario de " + uuid, exception);
        }
    }

    private Path path(UUID uuid) {
        return directory.resolve(uuid + ".yml");
    }
}
