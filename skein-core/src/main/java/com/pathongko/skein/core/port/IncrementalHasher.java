package com.pathongko.skein.core.port;

import com.pathongko.skein.core.domain.FileHash;

import java.nio.ByteBuffer;

/**
 * Computes a hash incrementally as bytes arrive, so a file can be hashed in a
 * single pass while it is being downloaded/written (no second read from disk).
 * Not thread-safe: one hasher belongs to one file download.
 */
public interface IncrementalHasher {

    /** Feed the next chunk. The buffer is consumed up to its limit. */
    void update(ByteBuffer chunk);

    void update(byte[] chunk, int offset, int length);

    /** Finish and produce the hash. The hasher must not be used afterwards. */
    FileHash finish();
}
