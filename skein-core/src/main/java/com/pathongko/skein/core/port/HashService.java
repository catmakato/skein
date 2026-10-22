package com.pathongko.skein.core.port;

import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;

/**
 * Produces content hashes. A port so the algorithm/implementation (JDK
 * MessageDigest today, a native BLAKE3 binding tomorrow) can change without the
 * domain caring.
 */
public interface HashService {

    /** Hash a whole in-memory blob. Convenience for small inputs and tests. */
    FileHash hash(byte[] data, HashAlgorithm algorithm);

    /** Start a streaming hash for the given algorithm. */
    IncrementalHasher newHasher(HashAlgorithm algorithm);
}
