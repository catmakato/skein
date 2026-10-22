package com.pathongko.skein.transfer.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRetryPolicyTest {

    private final RetryPolicy policy = new DefaultRetryPolicy(
            4, new ExponentialBackoff(Duration.ofMillis(500), Duration.ofSeconds(30), Jitter.none()));

    @Test
    void permanentFailuresAreNotRetried() {
        assertThat(policy.onFailure(new FailureContext(1, FailureKind.PERMANENT, 3)).shouldRetry()).isFalse();
        assertThat(policy.onFailure(new FailureContext(1, FailureKind.FATAL_LOCAL, 3)).shouldRetry()).isFalse();
    }

    @Test
    void stopsAfterMaxAttempts() {
        assertThat(policy.onFailure(new FailureContext(4, FailureKind.TRANSIENT, 3)).shouldRetry()).isFalse();
        assertThat(policy.onFailure(new FailureContext(3, FailureKind.TRANSIENT, 3)).shouldRetry()).isTrue();
    }

    @Test
    void integrityFailureDiscardsPartialAndRotatesMirrorWhenAvailable() {
        RetryDecision d = policy.onFailure(new FailureContext(1, FailureKind.INTEGRITY, 2));
        assertThat(d.shouldRetry()).isTrue();
        assertThat(d.discardPartial()).isTrue();
        assertThat(d.rotateMirror()).isTrue();
    }

    @Test
    void integrityWithNoMirrorsDoesNotRotate() {
        assertThat(policy.onFailure(new FailureContext(1, FailureKind.INTEGRITY, 0)).rotateMirror()).isFalse();
    }

    @Test
    void transientResumesWithoutDiscardingPartial() {
        RetryDecision d = policy.onFailure(new FailureContext(1, FailureKind.TRANSIENT, 1));
        assertThat(d.discardPartial()).isFalse();
        assertThat(d.delay()).isEqualTo(Duration.ofSeconds(1)); // 500ms * 2^(attempt) for attempt+1=2
    }

    @Test
    void resumableResetDiscardsPartialButKeepsMirror() {
        RetryDecision d = policy.onFailure(new FailureContext(1, FailureKind.RESUMABLE_RESET, 2));
        assertThat(d.discardPartial()).isTrue();
        assertThat(d.rotateMirror()).isFalse();
    }
}
