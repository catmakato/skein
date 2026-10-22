package com.pathongko.skein.core.sync;

/**
 * The states of one sync session. The orchestrator is the sole owner of this
 * value and moves through it in order; every transition is published as an event,
 * so "which state did it fail in?" always has an answer (design doc, ADR-04).
 */
public enum SyncState {
    IDLE,
    RESOLVING_MANIFEST,
    DIFFING,
    DOWNLOADING,
    APPLYING,
    DONE,
    FAILED,
    CANCELLED
}
