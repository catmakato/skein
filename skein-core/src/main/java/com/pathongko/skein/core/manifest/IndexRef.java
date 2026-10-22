package com.pathongko.skein.core.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileHash;

/**
 * A pointer from the small root manifest to one category index file. The client
 * fetches an index only when its {@link #hash()} differs from the cached copy —
 * this is delta at the manifest level (design doc, Part 2).
 */
public record IndexRef(FileCategory category, String file, FileHash hash, int entryCount) {

    public IndexRef {
        Preconditions.requireNonNull(category, "category");
        Preconditions.requireNonBlank(file, "file");
        Preconditions.requireNonNull(hash, "hash");
        Preconditions.requireNonNegative(entryCount, "entryCount");
    }
}
