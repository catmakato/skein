package com.pathongko.skein.core.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A root manifest together with all its category indices, resolved into a single
 * flat view of the desired file set. This is the input the diff engine compares
 * against the client's local state.
 */
public record ResolvedManifest(RootManifest root, List<CategoryIndex> indices) {

    public ResolvedManifest {
        Preconditions.requireNonNull(root, "root");
        Preconditions.requireNonNull(indices, "indices");
        indices = List.copyOf(indices);
    }

    /** Every desired file across all categories. */
    public List<FileEntry> allEntries() {
        return indices.stream().flatMap(i -> i.entries().stream()).toList();
    }

    /**
     * Desired files keyed by path. Insertion-ordered for stable iteration; a
     * duplicate path across categories is a manifest error and is rejected.
     */
    public Map<RelativePath, FileEntry> desiredByPath() {
        Map<RelativePath, FileEntry> byPath = new LinkedHashMap<>();
        for (FileEntry entry : allEntries()) {
            FileEntry previous = byPath.putIfAbsent(entry.path(), entry);
            if (previous != null) {
                throw new IllegalStateException("duplicate path in manifest: " + entry.path());
            }
        }
        return byPath;
    }
}
