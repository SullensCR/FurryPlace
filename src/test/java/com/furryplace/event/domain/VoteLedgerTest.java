package com.furryplace.event.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VoteLedgerTest {
    @Test
    void voteCanBeSelectedSwitchedAndRemoved() {
        VoteLedger ledger = new VoteLedger();
        UUID voter = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertEquals(VoteLedger.Result.SELECTED, ledger.toggle(voter, first, true));
        assertEquals(1, ledger.countFor(first));
        assertEquals(VoteLedger.Result.SWITCHED, ledger.toggle(voter, second, true));
        assertEquals(0, ledger.countFor(first));
        assertEquals(Set.of(voter), ledger.votersFor(second));
        assertEquals(VoteLedger.Result.REMOVED, ledger.toggle(voter, second, true));
        assertNull(ledger.selectionOf(voter));
    }

    @Test
    void communitySelfVoteIsRejectedWithoutChangingSelection() {
        VoteLedger ledger = new VoteLedger();
        UUID voter = UUID.randomUUID();
        assertEquals(VoteLedger.Result.REJECTED_SELF, ledger.toggle(voter, voter, true));
        assertTrue(ledger.snapshot().isEmpty());
    }
}
