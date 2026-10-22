package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.Optional;
import java.util.Set;

/**
 * Controls how extra local files (those not in the manifest) are treated.
 *
 * <p>{@code managedTopSegments} is the safety fence: only files whose top path
 * segment is in this set (e.g. "mods", "config") are ever considered for
 * deletion. Anything else the player has is left completely alone. Files under a
 * managed segment but flagged elsewhere as client-only are still kept.
 *
 * <p>{@code previouslyManaged}, when present, is a second fence: an extra file is
 * only deletion-eligible if it is also a member of this set — paths Skein itself
 * installed as of the last successful sync (design doc,
 * docs/superpowers/specs/2026-08-09-install-ledger-design.md). Absent means "no
 * ledger tracking": the original, ledger-unaware behavior of {@link #defaults()}
 * and {@link #additiveOnly()}, unchanged for backward compatibility.
 */
public record DiffOptions(
        DeletionPolicy extrasPolicy,
        Set<String> managedTopSegments,
        Optional<Set<RelativePath>> previouslyManaged) {

    private static final Set<String> STANDARD_MANAGED_SEGMENTS =
            Set.of("mods", "config", "resourcepacks", "shaderpacks", "datapacks");

    public DiffOptions {
        Preconditions.requireNonNull(extrasPolicy, "extrasPolicy");
        Preconditions.requireNonNull(managedTopSegments, "managedTopSegments");
        Preconditions.requireNonNull(previouslyManaged, "previouslyManaged");
        managedTopSegments = Set.copyOf(managedTopSegments);
        previouslyManaged = previouslyManaged.map(Set::copyOf);
    }

    /** Conservative default: quarantine extras only under the standard managed dirs. */
    public static DiffOptions defaults() {
        return new DiffOptions(DeletionPolicy.QUARANTINE, STANDARD_MANAGED_SEGMENTS, Optional.empty());
    }

    /** Never remove anything the player added; only add/update. */
    public static DiffOptions additiveOnly() {
        return new DiffOptions(DeletionPolicy.KEEP, Set.of(), Optional.empty());
    }

    /**
     * Quarantine extras under the standard managed dirs, but only when they are
     * also in {@code previouslyManaged} — paths Skein itself installed as of the
     * last successful sync. A file the player added is never in that set, so it is
     * never touched, no matter how long it sits under a managed dir.
     */
    public static DiffOptions safe(Set<RelativePath> previouslyManaged) {
        Preconditions.requireNonNull(previouslyManaged, "previouslyManaged");
        return new DiffOptions(DeletionPolicy.QUARANTINE, STANDARD_MANAGED_SEGMENTS, Optional.of(previouslyManaged));
    }

    public boolean manages(String topSegment) {
        return managedTopSegments.contains(topSegment);
    }
}
