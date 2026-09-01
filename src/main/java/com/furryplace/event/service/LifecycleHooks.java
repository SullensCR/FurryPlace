package com.furryplace.event.service;

import java.util.UUID;

public interface LifecycleHooks {
    LifecycleHooks NOOP = new LifecycleHooks() {};

    default void constructionEnded() {}
    default void reviewMoved(UUID plotOwner) {}
    default void reviewPaused() {}
    default void judgingStarted() {}
    default void winnerConfirmed(UUID winner) {}
    default void resetRequested() {}
}

