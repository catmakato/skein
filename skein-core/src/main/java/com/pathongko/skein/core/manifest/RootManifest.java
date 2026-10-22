package com.pathongko.skein.core.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The small, always-fetched-first manifest. It carries the pack identity/version
 * and the hash of every category index, plus an optional signature so the client
 * can verify the manifest is authentic before trusting anything it points to.
 */
public record RootManifest(
        int schemaVersion,
        String packId,
        PackVersion packVersion,
        Instant generatedAt,
        HashAlgorithm hashAlgorithm,
        List<IndexRef> indices,
        Optional<String> signature) {

    /** The schema version this build understands; older manifests are migrated up. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RootManifest {
        Preconditions.requireNonBlank(packId, "packId");
        Preconditions.requireNonNull(packVersion, "packVersion");
        Preconditions.requireNonNull(generatedAt, "generatedAt");
        Preconditions.requireNonNull(hashAlgorithm, "hashAlgorithm");
        Preconditions.requireNonNull(indices, "indices");
        Preconditions.requireNonNull(signature, "signature");
        indices = List.copyOf(indices);
    }

    public boolean isSigned() {
        return signature.isPresent();
    }
}
