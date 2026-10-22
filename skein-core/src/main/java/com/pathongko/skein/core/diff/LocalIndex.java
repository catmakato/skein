package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A snapshot of the files the client currently has under Skein-managed
 * directories: path to its content hash. Built by the storage layer (using the
 * fingerprint cache to avoid re-hashing unchanged files) and fed to the diff
 * engine. Pure data, so diffing needs no filesystem.
 */
public record LocalIndex(Map<RelativePath, FileHash> hashesByPath) {

    public LocalIndex {
        Preconditions.requireNonNull(hashesByPath, "hashesByPath");
        hashesByPath = Map.copyOf(hashesByPath);
    }

    public static LocalIndex empty() {
        return new LocalIndex(Map.of());
    }

    public Optional<FileHash> hashOf(RelativePath path) {
        return Optional.ofNullable(hashesByPath.get(path));
    }

    public Set<RelativePath> paths() {
        return hashesByPath.keySet();
    }
}
