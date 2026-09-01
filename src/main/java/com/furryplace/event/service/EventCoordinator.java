package com.furryplace.event.service;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.persistence.StateRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class EventCoordinator {
    public record Outcome(boolean success, String messageKey) {
        public static Outcome ok() { return new Outcome(true, null); }
        public static Outcome fail(String key) { return new Outcome(false, key); }
    }

    private static final Map<Long, String> THRESHOLDS = Map.ofEntries(
        Map.entry(Duration.ofMinutes(10).toMillis(), "event.minute-10"),
        Map.entry(Duration.ofMinutes(5).toMillis(), "event.minute-5"),
        Map.entry(Duration.ofMinutes(1).toMillis(), "event.minute-1"),
        Map.entry(Duration.ofSeconds(30).toMillis(), "event.second-30"),
        Map.entry(Duration.ofSeconds(15).toMillis(), "event.second-15"),
        Map.entry(Duration.ofSeconds(5).toMillis(), "event.second-5"),
        Map.entry(Duration.ofSeconds(4).toMillis(), "event.second-4"),
        Map.entry(Duration.ofSeconds(3).toMillis(), "event.second-3"),
        Map.entry(Duration.ofSeconds(2).toMillis(), "event.second-2"),
        Map.entry(Duration.ofSeconds(1).toMillis(), "event.second-1")
    );

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final StateRepository repository;
    private final MessageService messages;
    private final Set<Long> announced = new HashSet<>();
    private LifecycleHooks hooks = LifecycleHooks.NOOP;
    private BukkitTask timerTask;
    private long lastRemaining;

    public EventCoordinator(JavaPlugin plugin, RuntimeState state, StateRepository repository, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.repository = repository;
        this.messages = messages;
    }

    public void hooks(LifecycleHooks value) {
        hooks = value == null ? LifecycleHooks.NOOP : value;
    }

    public void startTicker() {
        recoverDeadline();
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stopTicker() {
        if (timerTask != null) timerTask.cancel();
    }

    public Outcome startEvent() {
        if (state.stage() != EventStage.INACTIVE) return Outcome.fail("errors.invalid-stage");
        if (!state.templateInitialized() || state.snapshotVersion() <= 0) return Outcome.fail("errors.template-required");
        state.stage(EventStage.ACTIVE);
        state.deadlineEpochMillis(System.currentTimeMillis() + Duration.ofMinutes(state.configuredMinutes()).toMillis());
        announced.clear();
        lastRemaining = state.remainingMillis(System.currentTimeMillis());
        repository.save(state);
        messages.broadcast("event.started", Map.of("minutes", state.configuredMinutes()));
        return Outcome.ok();
    }

    public Outcome configureDuration(int minutes, boolean active) {
        int minimum = active ? plugin.getConfig().getInt("timer.minimum-active-minutes", 1) : plugin.getConfig().getInt("timer.minimum-start-minutes", 5);
        int maximum = plugin.getConfig().getInt("timer.maximum-minutes", 180);
        int clamped = Math.max(minimum, Math.min(maximum, minutes));
        state.configuredMinutes(clamped);
        if (active) {
            long newRemaining = Duration.ofMinutes(clamped).toMillis();
            state.deadlineEpochMillis(System.currentTimeMillis() + newRemaining);
            THRESHOLDS.keySet().forEach(threshold -> {
                if (threshold < newRemaining) announced.remove(threshold); else announced.add(threshold);
            });
            lastRemaining = newRemaining;
            messages.broadcast("event.timer-adjusted", Map.of("time", clamped + " minutos"));
        }
        repository.save(state);
        return Outcome.ok();
    }

    public Outcome beginReview(UUID firstPlotOwner, UUID admin) {
        if (state.stage() != EventStage.REVIEW_PENDING) return Outcome.fail("errors.invalid-stage");
        List<UUID> order = state.completedOwnerOrder();
        if (order.isEmpty() || !order.contains(firstPlotOwner)) return Outcome.fail("errors.no-plot");
        state.review().start(order, firstPlotOwner, admin);
        transition(EventStage.REVIEWING);
        hooks.reviewMoved(firstPlotOwner);
        return Outcome.ok();
    }

    public Outcome moveReview(UUID admin, boolean next) {
        if (state.stage() != EventStage.REVIEWING || !state.review().controlledBy(admin)) return Outcome.fail("errors.no-permission");
        UUID owner = next ? state.review().next() : state.review().previous();
        repository.save(state);
        hooks.reviewMoved(owner);
        return Outcome.ok();
    }

    public Outcome takeReviewControl(UUID admin) {
        if (state.stage() != EventStage.REVIEWING || !state.review().paused()) return Outcome.fail("errors.invalid-stage");
        state.review().takeOver(admin);
        repository.save(state);
        return Outcome.ok();
    }

    public void controllerDisconnected(UUID admin) {
        if (state.stage() == EventStage.REVIEWING && state.review().controlledBy(admin)) {
            state.review().pauseIfController(admin);
            repository.save(state);
            hooks.reviewPaused();
        }
    }

    public Outcome endReview(UUID admin) {
        if (state.stage() != EventStage.REVIEWING || !state.review().controlledBy(admin)) return Outcome.fail("errors.no-permission");
        if (!state.review().allVisited()) return Outcome.fail("review.incomplete");
        transition(EventStage.JUDGING);
        hooks.judgingStarted();
        return Outcome.ok();
    }

    public Set<UUID> leadingJudgeCandidates() {
        int maximum = state.completedPlotsInAllocationOrder().stream().mapToInt(plot -> state.judgeVotes().countFor(plot.ownerId())).max().orElse(0);
        if (maximum == 0) return Set.of();
        Set<UUID> result = new HashSet<>();
        state.completedPlotsInAllocationOrder().stream().map(PlotRecord::ownerId).filter(owner -> state.judgeVotes().countFor(owner) == maximum).forEach(result::add);
        return result;
    }

    public Outcome confirmWinner(UUID admin, UUID winner) {
        if (state.stage() != EventStage.JUDGING) return Outcome.fail("errors.invalid-stage");
        Optional<PlotRecord> plot = state.plot(winner).filter(PlotRecord::complete);
        if (plot.isEmpty()) return Outcome.fail("errors.no-plot");
        state.winner(winner);
        if (Bukkit.getPlayer(winner) == null) state.pendingWinnerNotifications().add(winner);
        transition(EventStage.COMPLETE);
        hooks.winnerConfirmed(winner);
        return Outcome.ok();
    }

    public void requestReset() {
        hooks.resetRequested();
    }

    public RuntimeState state() {
        return state;
    }

    private void tick() {
        if (state.stage() != EventStage.ACTIVE) return;
        if (state.plots().stream().anyMatch(plot -> plot.status() == PlotRecord.Status.CLEARING)) return;
        long now = System.currentTimeMillis();
        long remaining = state.remainingMillis(now);
        THRESHOLDS.entrySet().stream().sorted(Map.Entry.<Long, String>comparingByKey(Comparator.reverseOrder())).forEach(entry -> {
            long threshold = entry.getKey();
            if (lastRemaining > threshold && remaining <= threshold && announced.add(threshold)) {
                messages.broadcast(entry.getValue());
            }
        });
        lastRemaining = remaining;
        if (remaining == 0L) {
            transition(EventStage.REVIEW_PENDING);
            messages.broadcast("event.construction-ended");
            hooks.constructionEnded();
        }
    }

    private void transition(EventStage target) {
        if (!state.stage().canTransitionTo(target)) {
            throw new IllegalStateException("Invalid transition " + state.stage() + " -> " + target);
        }
        state.stage(target);
        repository.save(state);
    }

    private void recoverDeadline() {
        if (state.stage() != EventStage.ACTIVE) return;
        long remaining = state.remainingMillis(System.currentTimeMillis());
        if (remaining == 0L) {
            state.stage(EventStage.REVIEW_PENDING);
            repository.save(state);
            Bukkit.getScheduler().runTask(plugin, hooks::constructionEnded);
            return;
        }
        lastRemaining = remaining;
        THRESHOLDS.keySet().stream().filter(threshold -> threshold >= remaining).forEach(announced::add);
    }
}
