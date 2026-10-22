package com.pathongko.skein.core.sync.port;

import com.pathongko.skein.core.manifest.ResolvedManifest;

/**
 * Fetches, validates and assembles the server manifest into a {@link
 * ResolvedManifest}. Behind this port sit the HTTP fetch, signature check and
 * cache; the orchestrator only sees the resolved result.
 *
 * @throws com.pathongko.skein.core.error.SkeinException if the manifest cannot be
 *         obtained or is invalid
 */
public interface ManifestResolver {

    ResolvedManifest resolve();
}
