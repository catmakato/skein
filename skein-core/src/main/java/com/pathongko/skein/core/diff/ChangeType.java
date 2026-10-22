package com.pathongko.skein.core.diff;

/**
 * Why a file needs fetching.
 */
public enum ChangeType {
    /** Desired by the manifest but not present locally. */
    ADD,
    /** Present locally but with a different content hash. */
    UPDATE
}
