package com.pathongko.skein.transfer.download;

import com.pathongko.skein.transfer.retry.FailureKind;

import java.util.Optional;

/**
 * Maps an HTTP status code to how the failure should be treated. Returns empty
 * for the success codes (200/206). Centralizes this table so classification is
 * consistent and testable.
 */
final class StatusClassifier {

    private StatusClassifier() {
    }

    static Optional<FailureKind> classify(int statusCode) {
        return switch (statusCode) {
            case 200, 206 -> Optional.empty();
            case 416 -> Optional.of(FailureKind.RESUMABLE_RESET);
            case 400, 401, 403, 404, 410 -> Optional.of(FailureKind.PERMANENT);
            default -> Optional.of(FailureKind.TRANSIENT); // 429, 5xx, and anything else
        };
    }
}
