package com.pathongko.skein.core.error;

/**
 * Stable, machine-readable error identity. Decoupled from human-facing text so it
 * can key i18n messages, documentation and metrics without those changing when
 * wording does (design doc, Part 5).
 */
public enum ErrorCode {
    MANIFEST_UNAVAILABLE,
    MANIFEST_INVALID,
    DOWNLOAD_FAILED,
    HASH_MISMATCH,
    APPLY_FAILED,
    LOCAL_IO,
    CANCELLED,
    UNKNOWN,
    ACCESS_DENIED
}
