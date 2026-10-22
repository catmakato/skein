package com.pathongko.skein.core.port;

import com.pathongko.skein.core.diff.DiffOptions;
import com.pathongko.skein.core.diff.LocalIndex;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.manifest.ResolvedManifest;

/**
 * Computes the {@link SyncPlan} that reconciles the client's local state with the
 * server manifest. Pure function of its inputs — no I/O — which makes the whole
 * of Skein's decision logic unit-testable without a filesystem or network.
 */
public interface DiffEngine {

    SyncPlan diff(ResolvedManifest desired, LocalIndex local, DiffOptions options);
}
