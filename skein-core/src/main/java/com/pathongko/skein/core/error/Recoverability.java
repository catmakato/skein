package com.pathongko.skein.core.error;

/**
 * Who (or what) can recover from a failure — drives whether the UI offers a retry,
 * asks the player to act, or tells them to contact the server admin.
 */
public enum Recoverability {
    RETRYABLE,
    FATAL,
    NEEDS_USER,
    NEEDS_ADMIN
}
