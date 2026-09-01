package com.furryplace.event.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventStageTest {
    @Test
    void normalLifecycleAllowsOnlyItsNextStage() {
        EventStage[] stages = EventStage.values();
        for (int index = 0; index < stages.length - 1; index++) {
            assertTrue(stages[index].canTransitionTo(stages[index + 1]));
            for (EventStage candidate : stages) {
                if (candidate != stages[index + 1] && candidate != EventStage.INACTIVE) {
                    assertFalse(stages[index].canTransitionTo(candidate), stages[index] + " -> " + candidate);
                }
            }
        }
    }

    @Test
    void resetIsAllowedOnlyFromAnActiveLifecycle() {
        assertFalse(EventStage.INACTIVE.canTransitionTo(EventStage.INACTIVE));
        for (EventStage stage : EventStage.values()) {
            if (stage != EventStage.INACTIVE) assertTrue(stage.canTransitionTo(EventStage.INACTIVE));
        }
    }
}
