package com.pathongko.skein.transfer.retry;

/**
 * How a download failure should be treated. Classifying the failure is kept
 * separate from deciding what to do about it (the retry policy), so the two can
 * evolve independently (design doc, Part 4).
 */
public enum FailureKind {
    /** Temporary and likely to succeed on retry: timeout, connection reset, 503, 429. */
    TRANSIENT,
    /** Won't fix itself: 404, 401/403, 400. Fail fast. */
    PERMANENT,
    /** Resume state is bad (e.g. HTTP 416): discard the partial and start over. */
    RESUMABLE_RESET,
    /** Bytes arrived but the hash didn't match: retry, preferably from another mirror. */
    INTEGRITY,
    /** Local problem the user must fix (disk full, permissions): do not retry. */
    FATAL_LOCAL
}
