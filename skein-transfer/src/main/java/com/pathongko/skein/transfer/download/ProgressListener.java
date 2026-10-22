package com.pathongko.skein.transfer.download;

/**
 * Notified as bytes are written, so progress can be aggregated and (throttled)
 * surfaced to the UI. Called from the download thread; implementations must be
 * cheap and thread-safe.
 */
@FunctionalInterface
public interface ProgressListener {

    void onBytes(long delta);

    ProgressListener NOOP = delta -> { };
}
