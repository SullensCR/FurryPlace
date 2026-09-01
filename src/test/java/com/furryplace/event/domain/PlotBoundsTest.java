package com.furryplace.event.domain;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class PlotBoundsTest {
    @Test
    void allFiftyPlotsHaveExpectedCoordinatesAndLookup() {
        for (int index = 1; index <= 50; index++) {
            PlotBounds bounds = PlotBounds.forIndex(index, 80, 1024, 2);
            assertEquals((index - 1) * 1024, bounds.originX());
            assertEquals(bounds.originX() + 79, bounds.maxX());
            assertTrue(bounds.containsInterior(bounds.originX(), 0));
            assertTrue(bounds.containsInterior(bounds.maxX(), 79));
            assertFalse(bounds.containsInterior(bounds.originX() - 1, 0));
            assertTrue(bounds.containsBoundary(bounds.originX() - 2, 0));
            assertEquals(OptionalInt.of(index), PlotBounds.locateIndex(bounds.originX() + 40, 40, 80, 1024, 2, 50));
        }
    }

    @Test
    void gapsAndCoordinatesBeyondLimitDoNotResolve() {
        assertTrue(PlotBounds.locateIndex(100, 10, 80, 1024, 2, 50).isEmpty());
        assertTrue(PlotBounds.locateIndex(50 * 1024, 10, 80, 1024, 2, 50).isEmpty());
        assertTrue(PlotBounds.locateIndex(10, 82, 80, 1024, 2, 50).isEmpty());
    }
}
