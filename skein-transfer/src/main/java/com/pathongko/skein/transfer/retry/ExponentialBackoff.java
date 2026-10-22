package com.pathongko.skein.transfer.retry;

import com.pathongko.skein.common.util.Preconditions;

import java.time.Duration;

/**
 * Exponential backoff with a cap and pluggable jitter:
 * {@code delay = jitter(min(cap, base * 2^(attempt-1)))}.
 *
 * <p>The cap is applied before jitter so a full-jitter delay never exceeds the
 * cap. Uses {@code long} math with saturation so large attempt numbers can't
 * overflow into a negative delay.
 */
public final class ExponentialBackoff implements BackoffStrategy {

    private final Duration base;
    private final Duration cap;
    private final Jitter jitter;

    public ExponentialBackoff(Duration base, Duration cap, Jitter jitter) {
        this.base = Preconditions.requireNonNull(base, "base");
        this.cap = Preconditions.requireNonNull(cap, "cap");
        this.jitter = Preconditions.requireNonNull(jitter, "jitter");
        Preconditions.checkArgument(!base.isNegative() && !base.isZero(), "base must be positive");
        Preconditions.checkArgument(cap.compareTo(base) >= 0, "cap must be >= base");
    }

    @Override
    public Duration delayFor(int attempt) {
        Preconditions.checkArgument(attempt >= 1, "attempt must be >= 1");
        long baseMillis = base.toMillis();
        long capMillis = cap.toMillis();

        long scaled;
        int shift = attempt - 1;
        if (shift >= 62 || baseMillis > capMillis >> shift) {
            scaled = capMillis; // would exceed the cap (or overflow) -> clamp
        } else {
            scaled = Math.min(capMillis, baseMillis << shift);
        }
        return jitter.apply(Duration.ofMillis(scaled));
    }
}
