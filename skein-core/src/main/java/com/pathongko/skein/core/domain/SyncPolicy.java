package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Preconditions;

/**
 * Per-file (or per-category) policy. Real modpacks are not "identical for every
 * file": some mods are client-only, some configs the player may edit, some files
 * must never be touched. Keeping policy in the domain makes those cases explicit
 * instead of special-cased in the sync logic.
 */
public record SyncPolicy(
        boolean required,
        DeletionPolicy onExtra,
        boolean clientOnlyAllowed) {

    public SyncPolicy {
        Preconditions.requireNonNull(onExtra, "onExtra");
    }

    /** Server-authoritative file that must match exactly and be removed if extra. */
    public static SyncPolicy mandatory() {
        return new SyncPolicy(true, DeletionPolicy.DELETE, false);
    }

    /** Optional file: not required, and the client may keep its own. */
    public static SyncPolicy optional() {
        return new SyncPolicy(false, DeletionPolicy.KEEP, true);
    }
}
