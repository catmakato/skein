package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Preconditions;

import java.util.List;
import java.util.Set;

/**
 * A single file in a modpack manifest: what it is, how big, its content hash,
 * its policy, and where to get it. Immutable and compared by value, which is
 * what makes diffing between local and remote trivial and thread-safe.
 */
public record FileEntry(
        RelativePath path,
        long sizeBytes,
        FileHash hash,
        FileCategory category,
        SyncPolicy policy,
        Set<DownloadSource> sources) {

    public FileEntry {
        Preconditions.requireNonNull(path, "path");
        Preconditions.requireNonNegative(sizeBytes, "sizeBytes");
        Preconditions.requireNonNull(hash, "hash");
        Preconditions.requireNonNull(category, "category");
        Preconditions.requireNonNull(policy, "policy");
        Preconditions.requireNonNull(sources, "sources");
        sources = Set.copyOf(sources);
    }

    /** Sources ordered by ascending priority (lower number tried first). */
    public List<DownloadSource> orderedSources() {
        return sources.stream()
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();
    }
}
