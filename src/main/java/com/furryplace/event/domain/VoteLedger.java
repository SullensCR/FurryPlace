package com.furryplace.event.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VoteLedger {
    public enum Result { SELECTED, SWITCHED, REMOVED, REJECTED_SELF }

    private final Map<UUID, UUID> selections = new LinkedHashMap<>();

    public Result toggle(UUID voter, UUID candidate, boolean rejectSelf) {
        if (rejectSelf && voter.equals(candidate)) {
            return Result.REJECTED_SELF;
        }
        UUID previous = selections.get(voter);
        if (candidate.equals(previous)) {
            selections.remove(voter);
            return Result.REMOVED;
        }
        selections.put(voter, candidate);
        return previous == null ? Result.SELECTED : Result.SWITCHED;
    }

    public UUID selectionOf(UUID voter) {
        return selections.get(voter);
    }

    public int countFor(UUID candidate) {
        return (int) selections.values().stream().filter(candidate::equals).count();
    }

    public Set<UUID> votersFor(UUID candidate) {
        Set<UUID> result = new LinkedHashSet<>();
        selections.forEach((voter, selected) -> {
            if (candidate.equals(selected)) {
                result.add(voter);
            }
        });
        return Collections.unmodifiableSet(result);
    }

    public Map<UUID, UUID> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(selections));
    }

    public void load(Map<UUID, UUID> values) {
        selections.clear();
        selections.putAll(values);
    }

    public void clear() {
        selections.clear();
    }
}

