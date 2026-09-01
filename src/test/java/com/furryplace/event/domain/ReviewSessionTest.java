package com.furryplace.event.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionTest {
    @Test
    void rotatesFromSelectedPlotAndRequiresEveryVisit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ReviewSession review = new ReviewSession();
        review.start(List.of(first, second, third), second, admin);
        assertEquals(List.of(second, third, first), review.order());
        assertEquals(second, review.current());
        assertFalse(review.allVisited());
        assertEquals(third, review.next());
        assertEquals(first, review.next());
        assertTrue(review.allVisited());
        assertEquals(third, review.previous());
    }

    @Test
    void controllerDisconnectPausesUntilExplicitTakeover() {
        UUID owner = UUID.randomUUID();
        UUID controller = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        ReviewSession review = new ReviewSession();
        review.start(List.of(owner), owner, controller);
        review.pauseIfController(controller);
        assertTrue(review.paused());
        assertFalse(review.controlledBy(controller));
        review.takeOver(replacement);
        assertFalse(review.paused());
        assertTrue(review.controlledBy(replacement));
    }
}
