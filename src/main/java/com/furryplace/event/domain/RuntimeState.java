package com.furryplace.event.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RuntimeState {
    private EventStage stage = EventStage.INACTIVE;
    private int configuredMinutes = 20;
    private long deadlineEpochMillis;
    private boolean templateInitialized;
    private long snapshotVersion;
    private final Map<UUID, PlotRecord> plots = new LinkedHashMap<>();
    private final VoteLedger communityVotes = new VoteLedger();
    private final VoteLedger judgeVotes = new VoteLedger();
    private final ReviewSession review = new ReviewSession();
    private UUID winner;
    private final Set<UUID> pendingWinnerNotifications = new LinkedHashSet<>();
    private StoredLocation lobbySpawn;
    private final Set<WorldBlockKey> portalBlocks = new LinkedHashSet<>();
    private String activeOperationType;
    private String activeOperationPayload;

    public EventStage stage() { return stage; }
    public void stage(EventStage value) { stage = value; }
    public int configuredMinutes() { return configuredMinutes; }
    public void configuredMinutes(int value) { configuredMinutes = value; }
    public long deadlineEpochMillis() { return deadlineEpochMillis; }
    public void deadlineEpochMillis(long value) { deadlineEpochMillis = value; }
    public boolean templateInitialized() { return templateInitialized; }
    public void templateInitialized(boolean value) { templateInitialized = value; }
    public long snapshotVersion() { return snapshotVersion; }
    public void snapshotVersion(long value) { snapshotVersion = value; }
    public VoteLedger communityVotes() { return communityVotes; }
    public VoteLedger judgeVotes() { return judgeVotes; }
    public ReviewSession review() { return review; }
    public UUID winner() { return winner; }
    public void winner(UUID value) { winner = value; }
    public Set<UUID> pendingWinnerNotifications() { return pendingWinnerNotifications; }
    public StoredLocation lobbySpawn() { return lobbySpawn; }
    public void lobbySpawn(StoredLocation value) { lobbySpawn = value; }
    public Set<WorldBlockKey> portalBlocks() { return portalBlocks; }
    public String activeOperationType() { return activeOperationType; }
    public void activeOperation(String type, String payload) { activeOperationType = type; activeOperationPayload = payload; }
    public String activeOperationPayload() { return activeOperationPayload; }

    public Collection<PlotRecord> plots() {
        return plots.values();
    }

    public Optional<PlotRecord> plot(UUID owner) {
        return Optional.ofNullable(plots.get(owner));
    }

    public Optional<PlotRecord> plotByIndex(int index) {
        return plots.values().stream().filter(plot -> plot.index() == index).findFirst();
    }

    public Optional<PlotRecord> plotByName(String name) {
        return plots.values().stream().filter(PlotRecord::complete).filter(plot -> plot.ownerName().equalsIgnoreCase(name)).findFirst();
    }

    public PlotRecord reservePlot(UUID owner, String ownerName, int maximum) {
        PlotRecord existing = plots.get(owner);
        if (existing != null) {
            existing.ownerName(ownerName);
            return existing;
        }
        boolean[] used = new boolean[maximum + 1];
        plots.values().forEach(plot -> {
            if (plot.index() <= maximum) used[plot.index()] = true;
        });
        int index = 1;
        while (index <= maximum && used[index]) index++;
        if (index > maximum) throw new IllegalStateException("Plot limit reached");
        PlotRecord record = new PlotRecord(index, owner, ownerName, PlotRecord.Status.RESERVED, snapshotVersion, Instant.now());
        plots.put(owner, record);
        return record;
    }

    public void removePlot(UUID owner) {
        plots.remove(owner);
        communityVotes.load(filterVotes(communityVotes.snapshot(), owner));
        judgeVotes.load(filterVotes(judgeVotes.snapshot(), owner));
    }

    public List<PlotRecord> completedPlotsInAllocationOrder() {
        return plots.values().stream().filter(PlotRecord::complete).sorted(Comparator.comparingInt(PlotRecord::index)).toList();
    }

    public List<UUID> completedOwnerOrder() {
        return completedPlotsInAllocationOrder().stream().map(PlotRecord::ownerId).toList();
    }

    public long remainingMillis(long nowEpochMillis) {
        return stage == EventStage.ACTIVE ? Math.max(0L, deadlineEpochMillis - nowEpochMillis) : 0L;
    }

    public void clearEventData() {
        stage = EventStage.INACTIVE;
        deadlineEpochMillis = 0L;
        templateInitialized = false;
        snapshotVersion = 0L;
        plots.clear();
        communityVotes.clear();
        judgeVotes.clear();
        review.clear();
        winner = null;
        pendingWinnerNotifications.clear();
        activeOperationType = null;
        activeOperationPayload = null;
    }

    public void restorePlots(Collection<PlotRecord> records) {
        plots.clear();
        records.forEach(record -> plots.put(record.ownerId(), record));
    }

    private Map<UUID, UUID> filterVotes(Map<UUID, UUID> source, UUID removed) {
        Map<UUID, UUID> filtered = new LinkedHashMap<>();
        source.forEach((voter, candidate) -> {
            if (!voter.equals(removed) && !candidate.equals(removed)) filtered.put(voter, candidate);
        });
        return filtered;
    }
}
