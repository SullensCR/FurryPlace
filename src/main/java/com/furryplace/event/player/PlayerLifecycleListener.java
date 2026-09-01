package com.furryplace.event.player;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.packet.PacketBridge;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.service.EventCoordinator;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.PlotService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerLifecycleListener implements Listener {
    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final StateRepository repository;
    private final PlotService plots;
    private final PlayerStateService playerStates;
    private final PacketBridge packets;
    private final EventCoordinator coordinator;
    private final MessageService messages;
    private final Map<UUID, Integer> membership = new HashMap<>();
    private final Map<UUID, Location> lastGrounded = new HashMap<>();
    private final Map<UUID, Long> recoveryCooldown = new HashMap<>();
    private final int xpLevel;

    public PlayerLifecycleListener(JavaPlugin plugin, RuntimeState state, StateRepository repository,
                                   PlotService plots, PlayerStateService playerStates, PacketBridge packets,
                                   EventCoordinator coordinator, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.repository = repository;
        this.plots = plots;
        this.playerStates = playerStates;
        this.packets = packets;
        this.coordinator = coordinator;
        this.messages = messages;
        xpLevel = plugin.getConfig().getInt("presentation.xp-level", 2026);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::forceXp), 40L, 40L);
    }

    public Location lobbySpawn() {
        Location saved = state.lobbySpawn() == null ? null : state.lobbySpawn().resolve();
        if (saved != null) return saved;
        World lobby = Bukkit.getWorld(plugin.getConfig().getString("worlds.lobby", "lobby"));
        return lobby == null ? null : lobby.getSpawnLocation();
    }

    public boolean isLobby(World world) {
        return world != null && world.getName().equals(plugin.getConfig().getString("worlds.lobby", "lobby"));
    }

    public void sendLobby(Player player) {
        Location lobby = lobbySpawn();
        if (lobby == null) {
            messages.send(player, "errors.worlds-missing");
            return;
        }
        packets.clearPlot(player);
        playerStates.leavePlace(player);
        membership.remove(player.getUniqueId());
        player.teleportAsync(lobby);
    }

    public void routeForStage(Player player, EventStage stage) {
        switch (stage) {
            case ACTIVE -> plots.join(player);
            case REVIEW_PENDING -> {
                sendLobby(player);
                messages.send(player, "event.review-pending");
            }
            case REVIEWING -> {
                UUID current = state.review().current();
                if (current != null) state.plot(current).ifPresent(plot -> plots.enter(player, plot, false));
            }
            case JUDGING, INACTIVE -> sendLobby(player);
            case COMPLETE -> {
                if (state.winner() == null || !plots.view(player, state.winner())) sendLobby(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerStates.recoverOnJoin(player);
        forceXp(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            sendLobby(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state.stage() == EventStage.REVIEWING || state.stage() == EventStage.COMPLETE) routeForStage(player, state.stage());
                if (state.pendingWinnerNotifications().remove(player.getUniqueId())) {
                    notifyWinner(player);
                    repository.save(state);
                }
            }, 1L);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        coordinator.controllerDisconnected(player.getUniqueId());
        playerStates.onQuit(player);
        packets.cancelSign(player.getUniqueId());
        membership.remove(player.getUniqueId());
        lastGrounded.remove(player.getUniqueId());
        recoveryCooldown.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;
        reconcile(event.getPlayer(), to);
        trackGroundAndVoid(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> reconcile(event.getPlayer(), event.getPlayer().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forceXp(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> reconcile(event.getPlayer(), event.getPlayer().getLocation()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location lobby = lobbySpawn();
        if (lobby != null) event.setRespawnLocation(lobby);
        Bukkit.getScheduler().runTask(plugin, () -> forceXp(event.getPlayer()));
    }

    @EventHandler
    public void onExperience(PlayerExpChangeEvent event) {
        event.setAmount(0);
        Bukkit.getScheduler().runTask(plugin, () -> forceXp(event.getPlayer()));
    }

    @EventHandler
    public void onLevel(PlayerLevelChangeEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> forceXp(event.getPlayer()));
    }

    public void reconcile(Player player, Location location) {
        PlotRecord newPlot = plots.plotAt(location).filter(plot -> plots.isInterior(plot, location)).orElse(null);
        Integer previous = membership.get(player.getUniqueId());
        Integer next = newPlot == null ? null : newPlot.index();
        if (java.util.Objects.equals(previous, next)) return;
        if (previous != null) {
            packets.clearPlot(player);
            playerStates.leavePlace(player);
        }
        if (newPlot == null) {
            membership.remove(player.getUniqueId());
            return;
        }
        membership.put(player.getUniqueId(), newPlot.index());
        if (state.stage() == EventStage.ACTIVE && newPlot.complete()
            && newPlot.ownerId().equals(player.getUniqueId()) && !player.hasPermission("furryplace.judge")
            && !player.hasPermission("furryplace.admin") && player.hasPermission("furryplace.player")) playerStates.activateOwner(player);
        else playerStates.activateViewer(player);
        packets.applyPlot(player, newPlot);
    }

    private void trackGroundAndVoid(Player player, Location location) {
        World world = plots.plotWorld().orElse(null);
        if (world == null || location.getWorld() != world) return;
        if (player.isOnGround() && !location.clone().subtract(0, 1, 0).getBlock().isPassable()) {
            lastGrounded.put(player.getUniqueId(), location.clone());
        }
        if (location.getY() >= world.getMinHeight() || player.getVelocity().getY() >= 0 || player.isFlying()) return;
        long now = Bukkit.getCurrentTick();
        if (recoveryCooldown.getOrDefault(player.getUniqueId(), 0L) > now) return;
        recoveryCooldown.put(player.getUniqueId(), now + plugin.getConfig().getLong("void-recovery.cooldown-ticks", 60L));
        Location target = lastGrounded.get(player.getUniqueId());
        if (target != null && target.getWorld() == world && !target.clone().subtract(0, 1, 0).getBlock().isPassable()) {
            double rise = Math.max(0.0, target.getY() - location.getY());
            double vertical = Math.min(8.0, Math.max(plugin.getConfig().getDouble("void-recovery.upward-velocity", 2.8),
                Math.sqrt(0.16 * rise) + 0.4));
            double travelTicks = Math.max(20.0, Math.min(80.0, vertical / 0.08 * 1.5));
            Vector horizontal = target.toVector().subtract(location.toVector()).setY(0).multiply(1.0 / travelTicks);
            double cap = plugin.getConfig().getDouble("void-recovery.horizontal-cap", 2.5);
            if (horizontal.length() > cap) horizontal.normalize().multiply(cap);
            horizontal.setY(vertical);
            player.setFallDistance(0);
            player.setVelocity(horizontal);
            return;
        }
        PlotRecord plot = plots.plotAt(location).orElse(null);
        if (plot != null) player.teleportAsync(plots.safeArrivalLocation(plot));
        player.setFallDistance(0);
    }

    private void forceXp(Player player) {
        if (player.getLevel() != xpLevel) player.setLevel(xpLevel);
        if (player.getExp() != 1.0f) player.setExp(1.0f);
    }

    private void notifyWinner(Player winner) {
        int votes = state.communityVotes().countFor(winner.getUniqueId());
        messages.send(winner, "winner.chat", Map.of("player", winner.getName(), "votes", Integer.toString(votes)));
        messages.title(winner, "winner.title", "winner.subtitle", Map.of("player", winner.getName()));
    }
}
