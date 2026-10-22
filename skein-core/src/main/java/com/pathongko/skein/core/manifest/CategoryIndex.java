package com.pathongko.skein.core.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;

import java.util.List;

/**
 * The full file list for one category (e.g. every mod). Split out from the root
 * so a 20 GB pack's tens-of-thousands of entries are fetched per-category and
 * only when that category actually changed.
 */
public record CategoryIndex(FileCategory category, List<FileEntry> entries) {

    public CategoryIndex {
        Preconditions.requireNonNull(category, "category");
        Preconditions.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
