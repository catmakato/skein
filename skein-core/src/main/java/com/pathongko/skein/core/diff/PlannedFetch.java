package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileEntry;

/**
 * A file the client must download (either brand new or a replacement for a
 * mismatched local copy). Carries the full {@link FileEntry} so the downloader
 * has the hash, size and sources.
 */
public record PlannedFetch(FileEntry entry, ChangeType type) {

    public PlannedFetch {
        Preconditions.requireNonNull(entry, "entry");
        Preconditions.requireNonNull(type, "type");
    }
}
