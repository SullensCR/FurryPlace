package com.furryplace.event.service;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.player.PlayerLifecycleListener;
import com.furryplace.event.player.PlayerStateService;
import com.furryplace.event.world.PlotService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;

public final class EventRuntimeHooks implements LifecycleHooks {
    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final PlotService plots;
    private final PlayerLifecycleListener players;
    private final PlayerStateService playerStates;
    private final MessageService messages;

    public EventRuntimeHooks(JavaPlugin plugin, RuntimeState state, PlotService plots,
                             PlayerLifecycleListener players, PlayerStateService playerStates, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.plots = plots;
        this.players = players;
        this.playerStates = playerStates;
        this.messages = messages;
        long period = plugin.getConfig().getLong("presentation.review-actionbar-period-ticks", 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::reviewActionBar, period, period);
    }

    @Override
    public void constructionEnded() {
        plots.cancelIncompleteAtTimeout();
        Bukkit.getOnlinePlayers().forEach(players::sendLobby);
    }

    @Override
    public void reviewMoved(UUID plotOwner) {
        PlotRecord plot = state.plot(plotOwner).orElse(null);
        if (plot == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) plots.enter(player, plot, false);
    }

    @Override
    public void reviewPaused() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("furryplace.admin")) messages.send(player, "review.paused");
        }
    }

    @Override
    public void judgingStarted() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            messages.clearActionBar(player);
            players.sendLobby(player);
        });
    }

    @Override
    public void winnerConfirmed(UUID winner) {
        PlotRecord plot = state.plot(winner).orElse(null);
        if (plot == null) return;
        int communityVotes = state.communityVotes().countFor(winner);
        messages.broadcast("winner.chat", Map.of("player", plot.ownerName(), "votes", Integer.toString(communityVotes)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages.title(player, "winner.title", "winner.subtitle", Map.of("player", plot.ownerName()));
            plots.enter(player, plot, false);
        }
    }

    @Override
    public void resetRequested() {
        messages.broadcast("event.reset-started");
        Bukkit.getOnlinePlayers().forEach(players::sendLobby);
        plots.reset(() -> {
            playerStates.clearEventData();
            messages.broadcast("event.reset-complete");
        });
    }

    private void reviewActionBar() {
        if (state.stage() != EventStage.REVIEWING || state.review().current() == null) return;
        PlotRecord plot = state.plot(state.review().current()).orElse(null);
        if (plot == null) return;
        Map<String, Object> values = Map.of("player", plot.ownerName(),
            "current", state.review().currentIndex() + 1, "total", state.review().order().size());
        Bukkit.getOnlinePlayers().forEach(player -> messages.actionBar(player, "review.actionbar", values));
    }
}
