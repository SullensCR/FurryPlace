package com.furryplace.event.domain;

import java.util.EnumSet;
import java.util.Set;

public enum EventStage {
    INACTIVE,
    ACTIVE,
    REVIEW_PENDING,
    REVIEWING,
    JUDGING,
    COMPLETE;

    public boolean canTransitionTo(EventStage target) {
        if (target == INACTIVE) {
            return this != INACTIVE;
        }
        return switch (this) {
            case INACTIVE -> target == ACTIVE;
            case ACTIVE -> target == REVIEW_PENDING;
            case REVIEW_PENDING -> target == REVIEWING;
            case REVIEWING -> target == JUDGING;
            case JUDGING -> target == COMPLETE;
            case COMPLETE -> false;
        };
    }

    public Set<EventStage> normalSuccessors() {
        return switch (this) {
            case INACTIVE -> EnumSet.of(ACTIVE);
            case ACTIVE -> EnumSet.of(REVIEW_PENDING);
            case REVIEW_PENDING -> EnumSet.of(REVIEWING);
            case REVIEWING -> EnumSet.of(JUDGING);
            case JUDGING -> EnumSet.of(COMPLETE);
            case COMPLETE -> EnumSet.noneOf(EventStage.class);
        };
    }
}

