package com.furryplace.event.world;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotBounds;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.service.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owns plot allocation and every large world mutation. */
public final class PlotService {
    public interface EntryHandler {
        void enterOwner(Player player, PlotRecord plot, Location arrival, boolean flightFallback);
        void enterViewer(Player player, PlotRecord plot, Location arrival, boolean flightFallback);
    }

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final StateRepository repository;
    private final MessageService messages;
    private final BlockOperationQueue operations;
    private final SnapshotStore snapshots;
    private final int size;
    private final int spacing;
    private final int maximum;
    private final int boundaryWidth;
    private final int surfaceY;
    private volatile TemplateSnapshot currentSnapshot;
    private boolean refreshing;
    private EntryHandler entryHandler;

    public PlotService(JavaPlugin plugin, RuntimeState state, StateRepository repository,
                       MessageService messages, BlockOperationQueue operations) {
        this.plugin = plugin;
        this.state = state;
        this.repository = repository;
        this.messages = messages;
        this.operations = operations;
        snapshots = new SnapshotStore(plugin.getDataFolder().toPath());
        size = plugin.getConfig().getInt("plots.size", 80);
        spacing = plugin.getConfig().getInt("plots.spacing", 1024);
        maximum = plugin.getConfig().getInt("plots.maximum", 50);
        boundaryWidth = plugin.getConfig().getInt("plots.boundary-width", 2);
        surfaceY = plugin.getConfig().getInt("plots.surface-y", 80);
    }

    public void entryHandler(EntryHandler value) {
        entryHandler = Objects.requireNonNull(value);
    }

    public boolean loadSnapshot() {
        if (!snapshots.exists(state.snapshotVersion())) return false;
        try {
            currentSnapshot = snapshots.load(state.snapshotVersion());
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("No se pudo cargar la plantilla: " + exception.getMessage());
            return false;
        }
    }

    public Optional<World> plotWorld() {
        return Optional.ofNullable(Bukkit.getWorld(plugin.getConfig().getString("worlds.plots", "place")));
    }

    public Optional<World> templateWorld() {
        return Optional.ofNullable(Bukkit.getWorld(plugin.getConfig().getString("worlds.template", "place-template")));
    }

    public PlotBounds bounds(int index) {
        return PlotBounds.forIndex(index, size, spacing, boundaryWidth);
    }

    public Optional<PlotRecord> plotAt(Location location) {
        World world = plotWorld().orElse(null);
        if (world == null || location.getWorld() != world) return Optional.empty();
        OptionalInt index = PlotBounds.locateIndex(location.getBlockX(), location.getBlockZ(), size, spacing,
            boundaryWidth, maximum);
        return index.isEmpty() ? Optional.empty() : state.plotByIndex(index.getAsInt());
    }

    public boolean isInterior(PlotRecord plot, Location location) {
        return plotWorld().map(world -> location.getWorld() == world).orElse(false)
            && bounds(plot.index()).containsInterior(location);
    }

    public Location safeArrivalLocation(PlotRecord plot) {
        World world = plotWorld().orElseThrow();
        return safeArrival(world, bounds(plot.index())).location();
    }

    public void join(Player player) {
        if (!player.hasPermission("furryplace.player")) {
            messages.send(player, "errors.no-permission");
            return;
        }
        if (state.stage() != EventStage.ACTIVE) {
            messages.send(player, "errors.invalid-stage");
            return;
        }
        if (currentSnapshot == null) {
            messages.send(player, "errors.template-required");
            return;
        }
        PlotRecord plot;
        try {
            plot = state.reservePlot(player.getUniqueId(), player.getName(), maximum);
        } catch (IllegalStateException exception) {
            messages.send(player, "errors.plot-limit");
            return;
        }
        if (plot.complete()) {
            enter(player, plot, true);
            return;
        }
        if (refreshing) {
            plot.status(PlotRecord.Status.GENERATING);
            repository.save(state);
            messages.send(player, "plot.generation-queued");
            return;
        }
        if (plot.status() == PlotRecord.Status.GENERATING) {
            messages.send(player, "plot.generation-queued");
            return;
        }
        plot.status(PlotRecord.Status.GENERATING);
        plot.snapshotVersion(currentSnapshot.version());
        repository.save(state);
        operations.enqueue(new ApplySnapshotOperation(plot, currentSnapshot, () -> {
            plot.status(PlotRecord.Status.COMPLETE);
            repository.save(state);
            Player online = Bukkit.getPlayer(plot.ownerId());
            if (online != null && state.stage() == EventStage.ACTIVE) {
                messages.clearActionBar(online);
                messages.send(online, "plot.generation-complete");
                enter(online, plot, true);
            }
        }));
        messages.send(player, "plot.generation-queued");
    }

    public boolean view(Player player, UUID owner) {
        PlotRecord plot = state.plot(owner).filter(PlotRecord::complete).orElse(null);
        if (plot == null) {
            messages.send(player, "errors.no-plot");
            return false;
        }
        enter(player, plot, false);
        return true;
    }

    public void enter(Player player, PlotRecord plot, boolean ownerRequest) {
        World world = plotWorld().orElse(null);
        if (world == null || entryHandler == null) {
            messages.send(player, "errors.worlds-missing");
            return;
        }
        Arrival arrival = safeArrival(world, bounds(plot.index()));
        if (ownerRequest && plot.ownerId().equals(player.getUniqueId()) && state.stage() == EventStage.ACTIVE) {
            entryHandler.enterOwner(player, plot, arrival.location(), arrival.flightFallback());
        } else {
            entryHandler.enterViewer(player, plot, arrival.location(), arrival.flightFallback());
        }
    }

    public boolean generateTemplate(Consumer<Boolean> completion) {
        World world = templateWorld().orElse(null);
        if (world == null || operations.busy()) return false;
        PlotBounds template = bounds(1);
        operations.enqueue(new DefaultTemplateOperation(world, template, () -> captureTemplate(completion)));
        return true;
    }

    public boolean refreshTemplate(Consumer<Boolean> completion) {
        World world = templateWorld().orElse(null);
        if (world == null) return false;
        operations.cancelAll();
        refreshing = true;
        captureTemplate(success -> {
            if (!success) {
                refreshing = false;
                completion.accept(false);
                return;
            }
            List<PlotRecord> incomplete = state.plots().stream().filter(plot -> !plot.complete()).toList();
            for (PlotRecord plot : incomplete) restartIncomplete(plot);
            refreshing = false;
            completion.accept(true);
        });
        return true;
    }

    public boolean freezeTemplate(Consumer<Boolean> completion) {
        if (templateWorld().isEmpty() || operations.busy()) return false;
        captureTemplate(completion);
        return true;
    }

    public void cancelIncompleteAtTimeout() {
        refreshing = false;
        operations.cancelAll();
        for (PlotRecord plot : new ArrayList<>(state.plots())) {
            if (plot.complete()) continue;
            Player player = Bukkit.getPlayer(plot.ownerId());
            if (player != null) messages.send(player, "plot.generation-cancelled");
            operations.enqueue(new ClearPlotOperation(plot, () -> {
                state.removePlot(plot.ownerId());
                repository.save(state);
            }));
        }
    }

    public void recoverInterruptedOperations() {
        String interrupted = state.activeOperationType();
        if (state.plots().stream().anyMatch(plot -> plot.status() == PlotRecord.Status.CLEARING)) {
            reset(() -> plugin.getLogger().info("El reinicio interrumpido terminó correctamente."));
            return;
        }
        if ("TEMPLATE_GENERATE".equals(interrupted)) {
            generateTemplate(success -> plugin.getLogger().info(success ? "La generación de plantilla reanudada terminó." : "No se pudo reanudar la plantilla."));
            return;
        }
        if ("TEMPLATE_CAPTURE".equals(interrupted)) {
            refreshTemplate(success -> plugin.getLogger().info(success ? "La captura de plantilla reanudada terminó." : "No se pudo reanudar la captura."));
            return;
        }
        if (currentSnapshot == null) return;
        for (PlotRecord plot : state.plots()) {
            if (!plot.complete()) restartIncomplete(plot);
        }
    }

    public void reset(Runnable completion) {
        operations.cancelAll();
        List<PlotRecord> plots = new ArrayList<>(state.plots());
        if (plots.isEmpty()) {
            finishReset(completion);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(plots.size());
        for (PlotRecord plot : plots) {
            plot.status(PlotRecord.Status.CLEARING);
            operations.enqueue(new ClearPlotOperation(plot, () -> {
                if (remaining.decrementAndGet() == 0) {
                    finishReset(completion);
                }
            }));
        }
        repository.save(state);
    }

    private void finishReset(Runnable completion) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                snapshots.deleteAll();
            } catch (IOException exception) {
                plugin.getLogger().severe("No se pudieron eliminar las instantáneas del evento: " + exception.getMessage());
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                currentSnapshot = null;
                state.clearEventData();
                repository.save(state);
                completion.run();
            });
        });
    }

    private void restartIncomplete(PlotRecord plot) {
        operations.enqueue(new ClearPlotOperation(plot, () -> {
            plot.status(PlotRecord.Status.GENERATING);
            plot.snapshotVersion(currentSnapshot.version());
            repository.save(state);
            operations.enqueue(new ApplySnapshotOperation(plot, currentSnapshot, () -> {
                plot.status(PlotRecord.Status.COMPLETE);
                repository.save(state);
            }));
        }));
    }

    private void captureTemplate(Consumer<Boolean> completion) {
        World world = templateWorld().orElse(null);
        if (world == null) {
            completion.accept(false);
            return;
        }
        long version = Math.max(System.currentTimeMillis(), state.snapshotVersion() + 1L);
        operations.enqueue(new CaptureSnapshotOperation(world, bounds(1), version, snapshot -> {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    snapshots.save(snapshot);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        currentSnapshot = snapshot;
                        state.snapshotVersion(snapshot.version());
                        state.templateInitialized(true);
                        repository.save(state);
                        Bukkit.getOnlinePlayers().forEach(messages::clearActionBar);
                        completion.accept(true);
                    });
                } catch (IOException exception) {
                    plugin.getLogger().severe("No se pudo guardar la plantilla: " + exception.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> completion.accept(false));
                }
            });
        }));
    }

    private Arrival safeArrival(World world, PlotBounds bounds) {
        int centerX = (int) Math.floor(bounds.centerX());
        int centerZ = (int) Math.floor(bounds.centerZ());
        for (int radius = 0; radius <= 12; radius++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    if (!bounds.containsInterior(x, z)) continue;
                    int highest = Math.max(surfaceY, world.getHighestBlockYAt(x, z));
                    if (highest + 2 >= world.getMaxHeight()) continue;
                    Block feet = world.getBlockAt(x, highest + 1, z);
                    Block head = world.getBlockAt(x, highest + 2, z);
                    if (!world.getBlockAt(x, highest, z).isPassable() && feet.isPassable() && head.isPassable()) {
                        return new Arrival(new Location(world, x + .5, highest + 1, z + .5), false);
                    }
                }
            }
        }
        return new Arrival(new Location(world, bounds.originX() - 1.5, surfaceY + 3.0, bounds.centerZ()), true);
    }

    private record Arrival(Location location, boolean flightFallback) {}

    private abstract class WorldOperation implements BlockOperationQueue.Operation {
        protected final World world;
        protected final PlotBounds bounds;
        protected final long total;
        protected long completed;
        private final Set<Chunk> tickets = new LinkedHashSet<>();
        private boolean loading;
        private boolean ready;
        private boolean cancelled;
        private Throwable loadFailure;

        protected WorldOperation(World world, PlotBounds bounds, long total) {
            this.world = world;
            this.bounds = bounds;
            this.total = total;
        }

        @Override
        public int step(int maximumBlocks, long deadlineNanos) {
            if (loadFailure != null) throw new IllegalStateException("No se pudieron cargar los chunks", loadFailure);
            if (!ready) {
                prepareChunks();
                return 0;
            }
            return work(maximumBlocks, deadlineNanos);
        }

        protected abstract int work(int maximumBlocks, long deadlineNanos);

        @Override public long completedBlocks() { return completed; }
        @Override public long totalBlocks() { return total; }
        @Override public boolean finished() { return completed >= total; }

        protected void releaseTickets() {
            for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin);
            tickets.clear();
        }

        @Override public void failed(Throwable throwable) { releaseTickets(); }
        @Override public void cancelled() { cancelled = true; releaseTickets(); }

        private void prepareChunks() {
            if (loading) return;
            loading = true;
            int minChunkX = (bounds.originX() - boundaryWidth) >> 4;
            int maxChunkX = (bounds.maxX() + boundaryWidth) >> 4;
            int minChunkZ = (bounds.originZ() - boundaryWidth) >> 4;
            int maxChunkZ = (bounds.maxZ() + boundaryWidth) >> 4;
            List<CompletableFuture<Chunk>> futures = new ArrayList<>();
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    futures.add(world.getChunkAtAsync(chunkX, chunkZ, true));
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (cancelled) return;
                    if (error != null) {
                        loadFailure = error;
                        return;
                    }
                    for (CompletableFuture<Chunk> future : futures) {
                        Chunk chunk = future.join();
                        chunk.addPluginChunkTicket(plugin);
                        tickets.add(chunk);
                    }
                    ready = true;
                }));
        }
    }

    private final class DefaultTemplateOperation extends WorldOperation {
        private final Runnable callback;
        private final int width = size + (boundaryWidth * 2);
        private final int height;

        private DefaultTemplateOperation(World world, PlotBounds bounds, Runnable callback) {
            super(world, bounds, (long) (size + boundaryWidth * 2) * (size + boundaryWidth * 2)
                * (world.getMaxHeight() - world.getMinHeight()));
            this.callback = callback;
            height = world.getMaxHeight() - world.getMinHeight();
        }

        @Override public String type() { return "TEMPLATE_GENERATE"; }
        @Override public String payload() { return "default"; }

        @Override
        protected int work(int maximumBlocks, long deadlineNanos) {
            int changed = 0;
            while (completed < total && changed < maximumBlocks && System.nanoTime() < deadlineNanos) {
                long column = completed / height;
                int y = world.getMinHeight() + (int) (completed % height);
                int relativeX = (int) (column / width) - boundaryWidth;
                int relativeZ = (int) (column % width) - boundaryWidth;
                boolean interior = relativeX >= 0 && relativeX < size && relativeZ >= 0 && relativeZ < size;
                Material material;
                if (y < surfaceY) material = Material.DIRT;
                else if (y == surfaceY) material = interior ? Material.GRASS_BLOCK : Material.ANDESITE;
                else material = Material.AIR;
                world.getBlockAt(bounds.originX() + relativeX, y, bounds.originZ() + relativeZ)
                    .setType(material, false);
                completed++;
                changed++;
            }
            if (changed > 0) {
                int percent = (int) Math.min(100L, completed * 100L / total);
                Bukkit.getOnlinePlayers().forEach(player -> messages.actionBar(player, "template.generating",
                    Map.of("percent", Integer.toString(percent))));
            }
            return changed;
        }

        @Override
        public void completed() {
            releaseTickets();
            callback.run();
        }
    }

    private final class CaptureSnapshotOperation extends WorldOperation {
        private final long version;
        private final Consumer<TemplateSnapshot> callback;
        private final TemplateSnapshot.Builder builder;
        private final int width = size + (boundaryWidth * 2);
        private final int height;

        private CaptureSnapshotOperation(World world, PlotBounds bounds, long version,
                                         Consumer<TemplateSnapshot> callback) {
            super(world, bounds, (long) (size + boundaryWidth * 2) * (size + boundaryWidth * 2)
                * (world.getMaxHeight() - world.getMinHeight()));
            this.version = version;
            this.callback = callback;
            builder = new TemplateSnapshot.Builder(version, world.getMinHeight(), world.getMaxHeight() - 1);
            height = world.getMaxHeight() - world.getMinHeight();
        }

        @Override public String type() { return "TEMPLATE_CAPTURE"; }
        @Override public String payload() { return Long.toString(version); }

        @Override
        protected int work(int maximumBlocks, long deadlineNanos) {
            int read = 0;
            while (completed < total && (read == 0 || read + height <= maximumBlocks)
                && System.nanoTime() < deadlineNanos) {
                long column = completed / height;
                int relativeX = (int) (column / width) - boundaryWidth;
                int relativeZ = (int) (column % width) - boundaryWidth;
                int startY = world.getMinHeight();
                int runStart = startY;
                String data = world.getBlockAt(bounds.originX() + relativeX, runStart,
                    bounds.originZ() + relativeZ).getBlockData().getAsString();
                for (int y = startY + 1; y < world.getMaxHeight(); y++) {
                    String next = world.getBlockAt(bounds.originX() + relativeX, y,
                        bounds.originZ() + relativeZ).getBlockData().getAsString();
                    if (!next.equals(data)) {
                        builder.addRun(relativeX, relativeZ, runStart, y - 1, data);
                        runStart = y;
                        data = next;
                    }
                }
                builder.addRun(relativeX, relativeZ, runStart, world.getMaxHeight() - 1, data);
                completed += height;
                read += height;
            }
            if (read > 0) {
                int percent = (int) Math.min(100L, completed * 100L / total);
                Bukkit.getOnlinePlayers().forEach(player -> messages.actionBar(player, "template.generating",
                    Map.of("percent", Integer.toString(percent))));
            }
            return read;
        }

        @Override
        public void completed() {
            releaseTickets();
            callback.accept(builder.build());
        }
    }

    private final class ApplySnapshotOperation extends WorldOperation {
        private final PlotRecord plot;
        private final TemplateSnapshot snapshot;
        private final Runnable callback;
        private final Map<Integer, BlockData> palette = new HashMap<>();
        private int runIndex;
        private int runY = Integer.MIN_VALUE;

        private ApplySnapshotOperation(PlotRecord plot, TemplateSnapshot snapshot, Runnable callback) {
            super(plotWorld().orElseThrow(), bounds(plot.index()), snapshot.blockCount());
            this.plot = plot;
            this.snapshot = snapshot;
            this.callback = callback;
        }

        @Override public String type() { return "PLOT_GENERATE"; }
        @Override public String payload() { return plot.ownerId() + ":" + snapshot.version(); }

        @Override
        protected int work(int maximumBlocks, long deadlineNanos) {
            int changed = 0;
            while (runIndex < snapshot.runs().size() && changed < maximumBlocks && System.nanoTime() < deadlineNanos) {
                TemplateSnapshot.BlockRun run = snapshot.runs().get(runIndex);
                if (runY == Integer.MIN_VALUE) runY = run.fromY();
                BlockData data = palette.computeIfAbsent(run.paletteIndex(), key ->
                    Bukkit.createBlockData(snapshot.palette().get(key)));
                world.getBlockAt(bounds.originX() + run.relativeX(), runY,
                    bounds.originZ() + run.relativeZ()).setBlockData(data, false);
                runY++;
                completed++;
                changed++;
                if (runY > run.toY()) {
                    runIndex++;
                    runY = Integer.MIN_VALUE;
                }
            }
            Player player = Bukkit.getPlayer(plot.ownerId());
            if (player != null && completed % 20_000L < maximumBlocks) {
                int percent = total == 0 ? 100 : (int) Math.min(100L, completed * 100L / total);
                messages.actionBar(player, "plot.generation-progress", Map.of("percent", Integer.toString(percent)));
            }
            return changed;
        }

        @Override
        public void completed() {
            releaseTickets();
            callback.run();
        }
    }

    private final class ClearPlotOperation extends WorldOperation {
        private final PlotRecord plot;
        private final Runnable callback;
        private final int width = size + (boundaryWidth * 2);
        private final int height;

        private ClearPlotOperation(PlotRecord plot, Runnable callback) {
            super(plotWorld().orElseThrow(), bounds(plot.index()), (long) (size + boundaryWidth * 2)
                * (size + boundaryWidth * 2) * (plotWorld().orElseThrow().getMaxHeight()
                - plotWorld().orElseThrow().getMinHeight()));
            this.plot = plot;
            this.callback = callback;
            height = world.getMaxHeight() - world.getMinHeight();
        }

        @Override public String type() { return "PLOT_CLEAR"; }
        @Override public String payload() { return plot.ownerId().toString(); }

        @Override
        protected int work(int maximumBlocks, long deadlineNanos) {
            int changed = 0;
            while (completed < total && changed < maximumBlocks && System.nanoTime() < deadlineNanos) {
                long column = completed / height;
                int y = world.getMinHeight() + (int) (completed % height);
                int relativeX = (int) (column / width) - boundaryWidth;
                int relativeZ = (int) (column % width) - boundaryWidth;
                world.getBlockAt(bounds.originX() + relativeX, y, bounds.originZ() + relativeZ)
                    .setType(Material.AIR, false);
                completed++;
                changed++;
            }
            return changed;
        }

        @Override
        public void completed() {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Player) && bounds.containsManaged(entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockZ())) entity.remove();
            }
            releaseTickets();
            callback.run();
        }
    }
}
