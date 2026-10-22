package com.pathongko.skein.transfer.resume;

import com.pathongko.skein.common.util.Preconditions;

/**
 * Decides how to continue a download given the partial file already on disk. Pure
 * arithmetic — no I/O — so every edge case (corrupt partial, server without range
 * support, already-complete file) is unit-testable.
 */
public record ResumePlan(Action action, long startOffset) {

    public enum Action {
        /** No partial existed: download the whole file from the start. */
        FRESH,
        /** A valid partial exists: request bytes from {@link #startOffset()} onward. */
        RESUME,
        /** The partial can't be trusted or resumed: discard it and start over. */
        RESTART,
        /** The partial is already the full size: skip download, just verify. */
        COMPLETE
    }

    public ResumePlan {
        Preconditions.requireNonNull(action, "action");
        Preconditions.requireNonNegative(startOffset, "startOffset");
    }

    public static ResumePlan compute(long existingBytes, long totalBytes, boolean serverSupportsRange) {
        Preconditions.requireNonNegative(totalBytes, "totalBytes");

        if (existingBytes <= 0) {
            return new ResumePlan(Action.FRESH, 0);
        }
        if (existingBytes >= totalBytes) {
            // Equal -> already have everything; greater -> partial is corrupt.
            return existingBytes == totalBytes
                    ? new ResumePlan(Action.COMPLETE, totalBytes)
                    : new ResumePlan(Action.RESTART, 0);
        }
        if (!serverSupportsRange) {
            return new ResumePlan(Action.RESTART, 0);
        }
        return new ResumePlan(Action.RESUME, existingBytes);
    }
}
