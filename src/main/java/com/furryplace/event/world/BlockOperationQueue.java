package com.furryplace.event.world;

import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.persistence.StateRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BlockOperationQueue {
    public interface Operation {
        String type();
        String payload();
        int step(int maximumBlocks, long deadlineNanos);
        long completedBlocks();
        long totalBlocks();
        boolean finished();
        void completed();
        default void failed(Throwable throwable) {}
        default void cancelled() {}
    }

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final StateRepository repository;
    private final Deque<Operation> operations = new ArrayDeque<>();
    private final int maximumBlocks;
    private final long maximumNanos;
    private BukkitTask task;
    private long ticks;

    public BlockOperationQueue(JavaPlugin plugin, RuntimeState state, StateRepository repository) {
        this.plugin = plugin;
        this.state = state;
        this.repository = repository;
        maximumBlocks = plugin.getConfig().getInt("operations.max-blocks-per-tick", 20_000);
        maximumNanos = plugin.getConfig().getLong("operations.max-millis-per-tick", 4L) * 1_000_000L;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public void enqueue(Operation operation) {
        operations.add(operation);
        if (operations.size() == 1) begin(operation);
    }

    public void clearQueued() {
        Operation active = operations.peek();
        operations.clear();
        if (active != null && !active.finished()) operations.add(active);
    }

    public void cancelAll() {
        operations.forEach(Operation::cancelled);
        operations.clear();
        state.activeOperation(null, null);
        repository.save(state);
    }

    public boolean busy() {
        return !operations.isEmpty();
    }

    public int queuedCount() {
        return operations.size();
    }

    private void tick() {
        Operation operation = operations.peek();
        if (operation == null) return;
        try {
            long deadline = System.nanoTime() + maximumNanos;
            operation.step(maximumBlocks, deadline);
            ticks++;
            if (ticks % 20L == 0L) {
                state.activeOperation(operation.type(), operation.payload());
                repository.save(state);
            }
            if (operation.finished()) {
                operation.completed();
                operations.remove();
                Operation next = operations.peek();
                if (next == null) {
                    state.activeOperation(null, null);
                    repository.save(state);
                } else {
                    begin(next);
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().severe("Falló la operación de bloques " + operation.type() + ": " + throwable.getMessage());
            operation.failed(throwable);
            operations.remove();
            Operation next = operations.peek();
            if (next == null) state.activeOperation(null, null); else begin(next);
            repository.save(state);
        }
    }

    private void begin(Operation operation) {
        state.activeOperation(operation.type(), operation.payload());
        repository.save(state);
    }
}
