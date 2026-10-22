package com.pathongko.skein.transfer.retry;

import com.pathongko.skein.common.util.Preconditions;

import java.time.Duration;

/**
 * The standard policy: never retry permanent or local-fatal failures; otherwise
 * retry up to {@code maxAttempts} using the backoff strategy, rotating mirrors
 * when one is available and discarding the partial file when the bytes on disk
 * can't be trusted.
 */
public final class DefaultRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final BackoffStrategy backoff;

    public DefaultRetryPolicy(int maxAttempts, BackoffStrategy backoff) {
        Preconditions.checkArgument(maxAttempts >= 1, "maxAttempts must be >= 1");
        this.maxAttempts = maxAttempts;
        this.backoff = Preconditions.requireNonNull(backoff, "backoff");
    }

    @Override
    public RetryDecision onFailure(FailureContext ctx) {
        if (ctx.kind() == FailureKind.PERMANENT || ctx.kind() == FailureKind.FATAL_LOCAL) {
            return RetryDecision.giveUp();
        }
        if (ctx.attempt() >= maxAttempts) {
            return RetryDecision.giveUp();
        }

        Duration delay = backoff.delayFor(ctx.attempt() + 1);
        boolean rotate = ctx.mirrorsAvailable() > 0
                && (ctx.kind() == FailureKind.INTEGRITY || ctx.kind() == FailureKind.TRANSIENT);
        boolean discardPartial = ctx.kind() == FailureKind.INTEGRITY
                || ctx.kind() == FailureKind.RESUMABLE_RESET;

        return RetryDecision.retryAfter(delay, rotate, discardPartial);
    }
}
