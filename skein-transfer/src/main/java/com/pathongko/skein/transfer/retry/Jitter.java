package com.pathongko.skein.transfer.retry;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Randomizes a computed backoff delay so many clients retrying at once don't
 * synchronize into a thundering herd. The randomness source is injectable so
 * tests are deterministic.
 */
public interface Jitter {

    Duration apply(Duration computed);

    /** No randomization — used in tests and when jitter is disabled. */
    static Jitter none() {
        return d -> d;
    }

    /**
     * Full jitter: pick uniformly in {@code [0, computed]}. {@code random} must
     * return values in {@code [0, 1)}.
     */
    static Jitter full(DoubleSupplier random) {
        return computed -> Duration.ofMillis((long) (computed.toMillis() * random.getAsDouble()));
    }
}
