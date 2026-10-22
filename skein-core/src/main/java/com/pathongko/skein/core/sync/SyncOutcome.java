package com.pathongko.skein.core.sync;

import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;

/**
 * The terminal result of a sync session. A sealed type so callers must handle
 * every case (the UI in particular: nothing to do, done, please restart, failed,
 * cancelled).
 */
public sealed interface SyncOutcome {

    boolean isSuccess();

    /** Already in sync — nothing was downloaded (the common fast path). */
    record UpToDate() implements SyncOutcome {
        public boolean isSuccess() {
            return true;
        }
    }

    /** Files changed and were applied; the game can continue without a restart. */
    record Applied() implements SyncOutcome {
        public boolean isSuccess() {
            return true;
        }
    }

    /** Files were applied but include mods, so the client must restart to load them. */
    record RestartRequired() implements SyncOutcome {
        public boolean isSuccess() {
            return true;
        }
    }

    /** The sync failed; {@code code} identifies why and {@code detail} describes it. */
    record Failed(ErrorCode code, Recoverability recoverability, String detail) implements SyncOutcome {
        public boolean isSuccess() {
            return false;
        }
    }

    /** The player (or the game) cancelled before completion. */
    record Cancelled() implements SyncOutcome {
        public boolean isSuccess() {
            return false;
        }
    }
}
