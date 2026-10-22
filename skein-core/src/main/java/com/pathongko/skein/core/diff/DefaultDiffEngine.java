package com.pathongko.skein.core.diff;

import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.port.DiffEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Straightforward three-way reconciliation of manifest vs local state. Output is
 * sorted by path so a plan is deterministic (important for stable tests, logs and
 * progress display).
 *
 * <ul>
 *   <li>desired &amp; absent locally -&gt; fetch (ADD)</li>
 *   <li>desired &amp; local hash differs -&gt; fetch (UPDATE)</li>
 *   <li>desired &amp; local hash matches -&gt; unchanged</li>
 *   <li>local &amp; not desired -&gt; deletion, but only under a managed top segment,
 *       only when the extras policy is not KEEP, and (when a ledger is configured)
 *       only if the path was previously Skein-managed</li>
 * </ul>
 */
public final class DefaultDiffEngine implements DiffEngine {

    private static final Comparator<RelativePath> BY_PATH = Comparator.comparing(RelativePath::value);

    @Override
    public SyncPlan diff(ResolvedManifest desired, LocalIndex local, DiffOptions options) {
        Map<RelativePath, FileEntry> desiredByPath = desired.desiredByPath();

        List<PlannedFetch> fetches = new ArrayList<>();
        List<RelativePath> unchanged = new ArrayList<>();

        desiredByPath.values().stream()
                .sorted(Comparator.comparing(e -> e.path().value()))
                .forEach(entry -> {
                    Optional<FileHash> localHash = local.hashOf(entry.path());
                    if (localHash.isEmpty()) {
                        fetches.add(new PlannedFetch(entry, ChangeType.ADD));
                    } else if (localHash.get().matches(entry.hash())) {
                        unchanged.add(entry.path());
                    } else {
                        fetches.add(new PlannedFetch(entry, ChangeType.UPDATE));
                    }
                });

        List<PlannedDeletion> deletions = new ArrayList<>();
        local.paths().stream()
                .filter(path -> !desiredByPath.containsKey(path))
                .sorted(BY_PATH)
                .forEach(path -> planDeletion(path, options).ifPresent(deletions::add));

        return new SyncPlan(fetches, deletions, unchanged);
    }

    private Optional<PlannedDeletion> planDeletion(RelativePath path, DiffOptions options) {
        boolean managed = options.manages(path.topSegment());
        boolean removable = options.extrasPolicy() != DeletionPolicy.KEEP;
        boolean ledgerAllows = options.previouslyManaged()
                .map(ledger -> ledger.contains(path))
                .orElse(true);
        if (managed && removable && ledgerAllows) {
            return Optional.of(new PlannedDeletion(path, options.extrasPolicy()));
        }
        return Optional.empty();
    }
}
