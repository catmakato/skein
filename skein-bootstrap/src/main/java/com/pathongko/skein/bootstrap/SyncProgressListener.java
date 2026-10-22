package com.pathongko.skein.bootstrap;

import com.pathongko.skein.core.sync.SyncEvent;

/** Receives sync events for display (progress screen, logs). */
@FunctionalInterface
public interface SyncProgressListener {
    void onEvent(SyncEvent event);
}
