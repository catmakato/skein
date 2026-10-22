package com.pathongko.skein.core.sync;

/**
 * Receives coarse byte-progress from the download service. The orchestrator turns
 * these into {@link SyncEvent.ProgressUpdated} events on the bus.
 */
@FunctionalInterface
public interface ProgressCallback {

    void onProgress(long bytesDone, long bytesTotal);

    ProgressCallback NOOP = (done, total) -> { };
}
