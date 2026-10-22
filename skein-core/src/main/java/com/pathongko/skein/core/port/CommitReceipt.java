package com.pathongko.skein.core.port;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Record of one committed file, enough to undo it: what path was written and
 * where the file it replaced (if any) was quarantined.
 */
public record CommitReceipt(RelativePath path, Path committedTarget, Optional<Path> quarantinedPrevious) {

    public CommitReceipt {
        Preconditions.requireNonNull(path, "path");
        Preconditions.requireNonNull(committedTarget, "committedTarget");
        Preconditions.requireNonNull(quarantinedPrevious, "quarantinedPrevious");
    }
}
