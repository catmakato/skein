package com.pathongko.skein.transfer.retry;

/**
 * Decides whether and how to retry a failed download. A port so the policy can be
 * swapped or decorated (e.g. adding a circuit breaker) without touching the
 * downloader.
 */
public interface RetryPolicy {

    RetryDecision onFailure(FailureContext context);
}
