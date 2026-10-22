package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.List;

/**
 * The outcome of a diff: exactly what must happen to bring the client in line
 * with the manifest. Immutable and self-describing so the orchestrator, progress
 * UI and logs all read from the same plan.
 */
public record SyncPlan(
        List<PlannedFetch> fetches,
        List<PlannedDeletion> deletions,
        List<RelativePath> unchanged) {

    public SyncPlan {
        Preconditions.requireNonNull(fetches, "fetches");
        Preconditions.requireNonNull(deletions, "deletions");
        Preconditions.requireNonNull(unchanged, "unchanged");
        fetches = List.copyOf(fetches);
        deletions = List.copyOf(deletions);
        unchanged = List.copyOf(unchanged);
    }

    /** Nothing to do — the client already matches the manifest. */
    public boolean isEmpty() {
        return fetches.isEmpty() && deletions.isEmpty();
    }

    public long bytesToDownload() {
        return fetches.stream().mapToLong(f -> f.entry().sizeBytes()).sum();
    }

    public long countOf(ChangeType type) {
        return fetches.stream().filter(f -> f.type() == type).count();
    }
}
