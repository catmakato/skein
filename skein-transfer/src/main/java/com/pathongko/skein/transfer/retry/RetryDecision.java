package com.pathongko.skein.transfer.retry;

import com.pathongko.skein.common.util.Preconditions;

import java.time.Duration;

/**
 * What to do after a failure: give up, or wait {@code delay} and try again —
 * optionally rotating to a different mirror and/or discarding the partial file.
 */
public record RetryDecision(boolean shouldRetry, Duration delay, boolean rotateMirror, boolean discardPartial) {

    public RetryDecision {
        Preconditions.requireNonNull(delay, "delay");
        Preconditions.checkArgument(!delay.isNegative(), "delay must not be negative");
    }

    public static RetryDecision giveUp() {
        return new RetryDecision(false, Duration.ZERO, false, false);
    }

    public static RetryDecision retryAfter(Duration delay, boolean rotateMirror, boolean discardPartial) {
        return new RetryDecision(true, delay, rotateMirror, discardPartial);
    }
}
