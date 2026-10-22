package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Hashes;
import com.pathongko.skein.common.util.Preconditions;

import java.util.Locale;

/**
 * A content hash tied to the algorithm that produced it.
 *
 * <p>Wrapping the hash in a value type (instead of passing raw {@code String}s)
 * makes it impossible to accidentally compare a path with a hash, and lets the
 * hash algorithm evolve without breaking structure. Comparison is
 * case-insensitive and constant-time.
 */
public record FileHash(HashAlgorithm algorithm, String value) {

    public FileHash {
        Preconditions.requireNonNull(algorithm, "algorithm");
        Preconditions.requireNonNull(value, "value");
        if (!algorithm.isValidHex(value)) {
            throw new IllegalArgumentException(
                    "invalid " + algorithm + " hash (expected " + algorithm.hexLength()
                            + " hex chars): " + value);
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static FileHash sha256(String hex) {
        return new FileHash(HashAlgorithm.SHA256, hex);
    }

    public static FileHash of(HashAlgorithm algorithm, byte[] digest) {
        return new FileHash(algorithm, Hashes.toHex(digest));
    }

    /** True if the same algorithm produced an equal digest. */
    public boolean matches(FileHash other) {
        return other != null
                && this.algorithm == other.algorithm
                && Hashes.constantTimeEquals(this.value, other.value);
    }
}
