package com.pathongko.skein.core.sync.port;

import com.pathongko.skein.core.diff.SyncPlan;

/**
 * Commits the staged downloads into the live instance and quarantines the plan's
 * deletions — all via the FileManager's atomic operations. Runs only after every
 * download has been verified.
 *
 * @throws com.pathongko.skein.core.error.SkeinException if applying fails
 */
public interface ApplyService {

    void apply(SyncPlan plan);
}
