package com.pathongko.skein.core.sync.port;

import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.sync.ProgressCallback;

/**
 * Downloads every file in the plan (in parallel, resuming/retrying/verifying each)
 * into staging. The parallelism and I/O live behind this port so the orchestrator
 * stays a sequential decision-maker.
 *
 * @throws com.pathongko.skein.core.error.SkeinException if any file ultimately fails
 */
public interface DownloadService {

    void execute(SyncPlan plan, ProgressCallback progress);
}
