package com.pathongko.skein.transfer.download;

import java.time.Duration;

/**
 * Waits between retry attempts. Injected so tests run instantly (a no-op sleeper)
 * and so the wait can later be made cancellation-aware.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** Returns immediately — for tests. */
    Sleeper NONE = duration -> { };

    /** Real wall-clock sleep. */
    static Sleeper real() {
        return duration -> {
            if (!duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration.toMillis());
            }
        };
    }
}
