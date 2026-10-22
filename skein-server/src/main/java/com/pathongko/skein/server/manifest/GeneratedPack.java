package com.pathongko.skein.server.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.RootManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The output of {@link ManifestGenerator}: the root manifest, the resolved
 * category indices, the encoded bytes of the root and each index (already hashed
 * and ready to serve), and a map from content hash to the file on disk that
 * serves it (the content-addressable store backing {@code GET /files/{hash}}).
 */
public record GeneratedPack(
        RootManifest root,
        List<CategoryIndex> indices,
        byte[] encodedRoot,
        Map<FileCategory, byte[]> encodedIndices,
        Map<FileHash, Path> contentByHash) {

    public GeneratedPack {
        Preconditions.requireNonNull(root, "root");
        Preconditions.requireNonNull(indices, "indices");
        Preconditions.requireNonNull(encodedRoot, "encodedRoot");
        indices = List.copyOf(indices);
        encodedIndices = Map.copyOf(encodedIndices);
        contentByHash = Map.copyOf(contentByHash);
    }
}
