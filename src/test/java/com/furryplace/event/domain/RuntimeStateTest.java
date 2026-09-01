package com.furryplace.event.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStateTest {
    @Test
    void allocationUsesEveryIndexOnceAndRejectsFiftyFirst() {
        RuntimeState state = new RuntimeState();
        Set<Integer> indexes = new HashSet<>();
        for (int index = 0; index < 50; index++) {
            indexes.add(state.reservePlot(UUID.randomUUID(), "Player" + index, 50).index());
        }
        assertEquals(50, indexes.size());
        assertThrows(IllegalStateException.class, () -> state.reservePlot(UUID.randomUUID(), "Extra", 50));
    }

    @Test
    void resetClearsEventDataButPreservesLobbyAndPortal() {
        RuntimeState state = new RuntimeState();
        state.templateInitialized(true);
        state.snapshotVersion(5L);
        state.stage(EventStage.ACTIVE);
        state.lobbySpawn(new StoredLocation("lobby", 1, 2, 3, 4, 5));
        state.portalBlocks().add(new WorldBlockKey("lobby", 1, 2, 3));
        state.reservePlot(UUID.randomUUID(), "Owner", 50);
        state.clearEventData();
        assertEquals(EventStage.INACTIVE, state.stage());
        assertFalse(state.templateInitialized());
        assertEquals(0L, state.snapshotVersion());
        assertTrue(state.plots().isEmpty());
        assertNotNull(state.lobbySpawn());
        assertEquals(1, state.portalBlocks().size());
    }
}
