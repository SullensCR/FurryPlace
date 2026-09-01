package com.furryplace.event.packet;

import com.furryplace.event.domain.PlotBounds;
import com.furryplace.event.domain.PlotRecord;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.biome.Biome;
import com.github.retrooper.packetevents.protocol.world.biome.Biomes;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.util.Vector2i;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkBiomes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** All intentionally client-only PacketEvents behavior. */
public final class PacketBridge extends PacketListenerAbstract {
    private record SignSession(Vector3i position, Location location, BlockData original,
                               Consumer<String> callback, BukkitTask timeout) {}

    private final JavaPlugin plugin;
    private final Map<UUID, SignSession> signs = new HashMap<>();
    private final Map<UUID, PlotRecord> viewedPlots = new HashMap<>();
    private final int plotSize;
    private final int plotSpacing;
    private final int boundaryWidth;
    private final String plotWorldName;

    public PacketBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        plotSize = plugin.getConfig().getInt("plots.size", 80);
        plotSpacing = plugin.getConfig().getInt("plots.spacing", 1024);
        boundaryWidth = plugin.getConfig().getInt("plots.boundary-width", 2);
        plotWorldName = plugin.getConfig().getString("worlds.plots", "place");
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        for (UUID uuid : signs.keySet().toArray(UUID[]::new)) cancelSign(uuid);
        viewedPlots.clear();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            WrapperPlayServerJoinGame wrapper = new WrapperPlayServerJoinGame(event);
            wrapper.setHardcore(true);
            event.markForReEncode(true);
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_BIOMES) {
            rewriteBiomeUpdate(event);
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) return;
        Player player = event.getPlayer();
        if (player == null) return;
        PlotRecord plot = viewedPlots.get(player.getUniqueId());
        if (plot == null || plot.environment().biome() == null) return;
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column original = wrapper.getColumn();
        PlotBounds bounds = PlotBounds.forIndex(plot.index(), plotSize, plotSpacing, boundaryWidth);
        if (!insidePlotChunks(bounds, original.getX(), original.getZ())) return;
        Biome biome = Biomes.getRegistry().getByName(event.getClientVersion(), plot.environment().biome().asString());
        if (biome == null) return;
        BaseChunk[] copied = new BaseChunk[original.getChunks().length];
        int biomeId = biome.getId(event.getClientVersion());
        for (int index = 0; index < copied.length; index++) {
            BaseChunk section = original.getChunks()[index];
            if (section instanceof Chunk_v1_18 chunk) {
                DataPalette overlay = new DataPalette(new SingletonPalette(biomeId), null, PaletteType.BIOME);
                copied[index] = new Chunk_v1_18(event.getClientVersion(), chunk.getBlockCount(), chunk.getFluidCount(),
                    chunk.getChunkData(), overlay);
            } else {
                copied[index] = section;
            }
        }
        Column clone = new Column(original.getX(), original.getZ(), original.isFullChunk(), copied,
            original.getTileEntities(), original.getHeightMaps());
        wrapper.setColumn(clone);
        event.markForReEncode(true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player player = event.getPlayer();
        if (player == null) return;
        SignSession session = signs.get(player.getUniqueId());
        if (session == null) return;
        WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
        if (!wrapper.getBlockPosition().equals(session.position())) return;
        event.setCancelled(true);
        String value = String.join(" ", wrapper.getTextLines()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            finishSign(player, session);
            session.callback().accept(value);
        });
    }

    public void openSign(Player player, Consumer<String> callback) {
        cancelSign(player.getUniqueId());
        Location location = player.getLocation().clone().add(0, -4, 0).toBlockLocation();
        Vector3i position = new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockData original = location.getBlock().getBlockData().clone();
        player.sendBlockChange(location, Material.OAK_SIGN.createBlockData());
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> timeoutSign(player.getUniqueId()), 20L * 30L);
        signs.put(player.getUniqueId(), new SignSession(position, location, original, callback, timeout));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerOpenSignEditor(position, true));
    }

    public void cancelSign(UUID uuid) {
        SignSession session = signs.remove(uuid);
        if (session == null) return;
        session.timeout().cancel();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.sendBlockChange(session.location(), session.original());
    }

    private void timeoutSign(UUID uuid) {
        SignSession session = signs.remove(uuid);
        if (session == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendBlockChange(session.location(), session.original());
            session.callback().accept("");
        }
    }

    public void applyPlot(Player player, PlotRecord plot) {
        viewedPlots.put(player.getUniqueId(), plot);
        applyWeatherAndTime(player, plot);
        sendBiomeOverlay(player, plot);
    }

    public void clearPlot(Player player) {
        PlotRecord old = viewedPlots.remove(player.getUniqueId());
        player.resetPlayerWeather();
        player.resetPlayerTime();
        if (old == null || player.getWorld() == null || !player.getWorld().getName().equals(plotWorldName)) return;
        PlotBounds bounds = PlotBounds.forIndex(old.index(), plotSize, plotSpacing, boundaryWidth);
        for (int chunkX = bounds.originX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.originZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                player.getWorld().refreshChunk(chunkX, chunkZ);
            }
        }
    }

    public void refreshEnvironment(PlotRecord plot) {
        for (Map.Entry<UUID, PlotRecord> entry : viewedPlots.entrySet()) {
            if (entry.getValue().index() != plot.index()) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                applyWeatherAndTime(player, plot);
                sendBiomeOverlay(player, plot);
            }
        }
    }

    private void applyWeatherAndTime(Player player, PlotRecord plot) {
        var weather = plot.environment().weather() == null
            ? com.furryplace.event.domain.EnvironmentSettings.WeatherChoice.CLEAR : plot.environment().weather();
        switch (weather) {
            case CLEAR -> {
                player.setPlayerWeather(org.bukkit.WeatherType.CLEAR);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.END_RAINING, 0.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE, 0.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.THUNDER_LEVEL_CHANGE, 0.0f);
            }
            case RAIN -> {
                player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.BEGIN_RAINING, 0.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE, 1.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.THUNDER_LEVEL_CHANGE, 0.0f);
            }
            case THUNDER -> {
                player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.BEGIN_RAINING, 0.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE, 1.0f);
                weatherPacket(player, WrapperPlayServerChangeGameState.Reason.THUNDER_LEVEL_CHANGE, 1.0f);
            }
        }
        if (plot.environment().time() != null) player.setPlayerTime(plot.environment().time().ticks(), false);
        else player.resetPlayerTime();
    }

    private void weatherPacket(Player player, WrapperPlayServerChangeGameState.Reason reason, float value) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerChangeGameState(reason, value));
    }

    private void sendBiomeOverlay(Player player, PlotRecord plot) {
        if (plot.environment().biome() == null) return;
        Biome biome = Biomes.getRegistry().getByName(PacketEvents.getAPI().getPlayerManager().getClientVersion(player),
            plot.environment().biome().asString());
        if (biome == null) return;
        PlotBounds bounds = PlotBounds.forIndex(plot.index(), plotSize, plotSpacing, boundaryWidth);
        int sections = (player.getWorld().getMaxHeight() - player.getWorld().getMinHeight()) >> 4;
        Map<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData> chunks = new LinkedHashMap<>();
        for (int chunkX = bounds.originX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.originZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                chunks.put(new Vector2i(chunkX, chunkZ), WrapperPlayServerChunkBiomes.ChunkBiomeData
                    .createWithSingleBiome(biome, PacketEvents.getAPI().getPlayerManager().getClientVersion(player), sections));
            }
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerChunkBiomes(chunks));
    }

    private void rewriteBiomeUpdate(PacketSendEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        PlotRecord plot = viewedPlots.get(player.getUniqueId());
        if (plot == null || plot.environment().biome() == null) return;
        Biome biome = Biomes.getRegistry().getByName(event.getClientVersion(), plot.environment().biome().asString());
        if (biome == null) return;
        PlotBounds bounds = PlotBounds.forIndex(plot.index(), plotSize, plotSpacing, boundaryWidth);
        int sections = (player.getWorld().getMaxHeight() - player.getWorld().getMinHeight()) >> 4;
        WrapperPlayServerChunkBiomes wrapper = new WrapperPlayServerChunkBiomes(event);
        Map<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData> copied = new LinkedHashMap<>(wrapper.getChunks());
        boolean changed = false;
        for (Vector2i position : new java.util.ArrayList<>(copied.keySet())) {
            if (!insidePlotChunks(bounds, position.getX(), position.getZ())) continue;
            copied.put(position, WrapperPlayServerChunkBiomes.ChunkBiomeData
                .createWithSingleBiome(biome, event.getClientVersion(), sections));
            changed = true;
        }
        if (changed) {
            wrapper.getChunks().clear();
            wrapper.getChunks().putAll(copied);
            event.markForReEncode(true);
        }
    }

    private boolean insidePlotChunks(PlotBounds bounds, int chunkX, int chunkZ) {
        return chunkX >= bounds.originX() >> 4 && chunkX <= bounds.maxX() >> 4
            && chunkZ >= bounds.originZ() >> 4 && chunkZ <= bounds.maxZ() >> 4;
    }

    private void finishSign(Player player, SignSession session) {
        signs.remove(player.getUniqueId());
        session.timeout().cancel();
        player.sendBlockChange(session.location(), session.original());
    }
}
