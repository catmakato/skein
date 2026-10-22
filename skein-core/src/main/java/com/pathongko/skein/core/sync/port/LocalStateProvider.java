package com.pathongko.skein.core.sync.port;

import com.pathongko.skein.core.diff.LocalIndex;
import com.pathongko.skein.core.manifest.ResolvedManifest;

/**
 * Captures the client's current on-disk state as a {@link LocalIndex}. The impl
 * uses the fingerprint cache to avoid re-hashing unchanged files. Given the
 * desired manifest so it can limit hashing to the managed directories.
 */
public interface LocalStateProvider {

    LocalIndex capture(ResolvedManifest desired);
}
