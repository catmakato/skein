package com.pathongko.skein.transfer.retry;

import java.time.Duration;

/**
 * Computes how long to wait before a given attempt.
 */
public interface BackoffStrategy {

    /** @param attempt 1-based attempt number that is about to be made */
    Duration delayFor(int attempt);
}
