package com.furryplace.event.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ReviewSession {
    private final List<UUID> order = new ArrayList<>();
    private final Set<UUID> visited = new LinkedHashSet<>();
    private int currentIndex;
    private UUID controller;
    private boolean paused;

    public void start(List<UUID> allocationOrder, UUID first, UUID controllerId) {
        if (allocationOrder.isEmpty() || !allocationOrder.contains(first)) {
            throw new IllegalArgumentException("Review needs a valid starting plot");
        }
        int start = allocationOrder.indexOf(first);
        order.clear();
        order.addAll(allocationOrder.subList(start, allocationOrder.size()));
        order.addAll(allocationOrder.subList(0, start));
        visited.clear();
        currentIndex = 0;
        controller = controllerId;
        paused = false;
        visited.add(current());
    }

    public UUID current() {
        if (order.isEmpty()) {
            return null;
        }
        return order.get(currentIndex);
    }

    public UUID next() {
        ensureStarted();
        currentIndex = (currentIndex + 1) % order.size();
        visited.add(current());
        return current();
    }

    public UUID previous() {
        ensureStarted();
        currentIndex = Math.floorMod(currentIndex - 1, order.size());
        visited.add(current());
        return current();
    }

    public boolean allVisited() {
        return !order.isEmpty() && visited.containsAll(order);
    }

    public void pauseIfController(UUID playerId) {
        if (playerId != null && playerId.equals(controller)) {
            paused = true;
        }
    }

    public void takeOver(UUID adminId) {
        controller = adminId;
        paused = false;
    }

    public boolean controlledBy(UUID playerId) {
        return !paused && playerId != null && playerId.equals(controller);
    }

    public List<UUID> order() { return Collections.unmodifiableList(order); }
    public Set<UUID> visited() { return Collections.unmodifiableSet(visited); }
    public int currentIndex() { return currentIndex; }
    public UUID controller() { return controller; }
    public boolean paused() { return paused; }

    public void restore(List<UUID> restoredOrder, Set<UUID> restoredVisited, int index, UUID controllerId, boolean isPaused) {
        order.clear();
        order.addAll(restoredOrder);
        visited.clear();
        visited.addAll(restoredVisited);
        currentIndex = order.isEmpty() ? 0 : Math.max(0, Math.min(index, order.size() - 1));
        controller = controllerId;
        paused = isPaused;
    }

    public void clear() {
        order.clear();
        visited.clear();
        currentIndex = 0;
        controller = null;
        paused = false;
    }

    private void ensureStarted() {
        if (order.isEmpty()) {
            throw new IllegalStateException("Review has not started");
        }
    }
}

