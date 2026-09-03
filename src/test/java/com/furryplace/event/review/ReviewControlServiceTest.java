package com.furryplace.event.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewControlServiceTest {
    @Test
    void speedCycleAndValuesMatchReviewContract() {
        assertEquals(0.05f, ReviewControlService.Speed.SLOW.value());
        assertEquals(0.10f, ReviewControlService.Speed.NORMAL.value());
        assertEquals(0.20f, ReviewControlService.Speed.FAST.value());
        assertEquals(ReviewControlService.Speed.NORMAL, ReviewControlService.Speed.SLOW.next());
        assertEquals(ReviewControlService.Speed.FAST, ReviewControlService.Speed.NORMAL.next());
        assertEquals(ReviewControlService.Speed.SLOW, ReviewControlService.Speed.FAST.next());
    }
}
