package com.pathongko.skein.transfer.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExponentialBackoffTest {

    private final ExponentialBackoff backoff = new ExponentialBackoff(
            Duration.ofMillis(500), Duration.ofSeconds(30), Jitter.none());

    @Test
    void doublesEachAttempt() {
        assertThat(backoff.delayFor(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(backoff.delayFor(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayFor(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayFor(4)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void clampsToCap() {
        assertThat(backoff.delayFor(20)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void hugeAttemptDoesNotOverflow() {
        assertThat(backoff.delayFor(1000)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void fullJitterStaysWithinComputedDelay() {
        // random() = 0.5 -> half of the computed (capped) delay.
        ExponentialBackoff jittered = new ExponentialBackoff(
                Duration.ofMillis(500), Duration.ofSeconds(30), Jitter.full(() -> 0.5));
        assertThat(jittered.delayFor(3)).isEqualTo(Duration.ofSeconds(1)); // half of 2s
    }
}
