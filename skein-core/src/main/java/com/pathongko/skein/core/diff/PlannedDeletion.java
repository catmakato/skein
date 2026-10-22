package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.RelativePath;

/**
 * A local file that is not in the manifest and, per policy, should be removed.
 * The policy is carried so the apply step knows whether to quarantine or hard
 * remove (Skein never hard-deletes immediately — see design doc, Part 4).
 */
public record PlannedDeletion(RelativePath path, DeletionPolicy policy) {

    public PlannedDeletion {
        Preconditions.requireNonNull(path, "path");
        Preconditions.requireNonNull(policy, "policy");
    }
}
