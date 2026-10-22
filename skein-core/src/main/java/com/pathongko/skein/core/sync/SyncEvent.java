package com.pathongko.skein.core.sync;

import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.Set;

/**
 * Something observable that happened during a sync. Publishing these (rather than
 * calling the UI/logger directly) is what makes progress display, logging and
 * metrics mere subscribers — add a new listener without touching the engine
 * (design doc, Part 5, Observer pattern).
 */
public sealed interface SyncEvent {

    record StateChanged(SyncState from, SyncState to) implements SyncEvent {
    }

    record ManifestResolved(String packId, PackVersion version) implements SyncEvent {
    }

    record DiffComputed(int fetches, int deletions, long bytesToDownload,
                        Set<RelativePath> desiredPaths, Set<RelativePath> fetchedPaths)
            implements SyncEvent {
        public DiffComputed {
            desiredPaths = Set.copyOf(desiredPaths);
            fetchedPaths = Set.copyOf(fetchedPaths);
        }
    }

    record ProgressUpdated(long bytesDone, long bytesTotal) implements SyncEvent {
    }

    record SyncFinished(SyncOutcome outcome) implements SyncEvent {
    }
}
