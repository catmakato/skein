package com.pathongko.skein.transfer.retry;

import com.pathongko.skein.common.util.Preconditions;

/**
 * The facts a {@link RetryPolicy} needs to decide the next move.
 *
 * @param attempt         the attempt that just failed, 1-based
 * @param kind            the classified failure
 * @param mirrorsAvailable how many other download sources could be tried instead
 */
public record FailureContext(int attempt, FailureKind kind, int mirrorsAvailable) {

    public FailureContext {
        Preconditions.checkArgument(attempt >= 1, "attempt must be >= 1");
        Preconditions.requireNonNull(kind, "kind");
        Preconditions.requireNonNegative(mirrorsAvailable, "mirrorsAvailable");
    }
}
