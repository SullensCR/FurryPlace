package com.furryplace.event.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PlotRecord {
    public enum Status { RESERVED, GENERATING, COMPLETE, CLEARING }

    private final int index;
    private final UUID ownerId;
    private final EnvironmentSettings environment = new EnvironmentSettings();
    private String ownerName;
    private Status status;
    private long snapshotVersion;
    private final Instant reservedAt;

    public PlotRecord(int index, UUID ownerId, String ownerName, Status status, long snapshotVersion, Instant reservedAt) {
        this.index = index;
        this.ownerId = Objects.requireNonNull(ownerId);
        this.ownerName = Objects.requireNonNull(ownerName);
        this.status = Objects.requireNonNull(status);
        this.snapshotVersion = snapshotVersion;
        this.reservedAt = Objects.requireNonNull(reservedAt);
    }

    public int index() { return index; }
    public UUID ownerId() { return ownerId; }
    public String ownerName() { return ownerName; }
    public void ownerName(String value) { ownerName = Objects.requireNonNull(value); }
    public Status status() { return status; }
    public void status(Status value) { status = Objects.requireNonNull(value); }
    public long snapshotVersion() { return snapshotVersion; }
    public void snapshotVersion(long value) { snapshotVersion = value; }
    public Instant reservedAt() { return reservedAt; }
    public EnvironmentSettings environment() { return environment; }
    public boolean complete() { return status == Status.COMPLETE; }
}

