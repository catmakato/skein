package com.pathongko.skein.core.domain;

/**
 * What to do with a client-side file that is not present in the server manifest.
 */
public enum DeletionPolicy {
    /** Remove it (moved to quarantine, never hard-deleted immediately). */
    DELETE,
    /** Leave it alone — the player is allowed to have it. */
    KEEP,
    /** Move it aside into quarantine but treat as removed for sync purposes. */
    QUARANTINE
}
