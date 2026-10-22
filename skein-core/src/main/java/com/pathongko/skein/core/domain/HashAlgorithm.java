package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Hashes;

/**
 * Supported content-hash algorithms. Each value knows its JCA name and the
 * expected hex length, so validation lives in one place rather than being
 * hardcoded across the codebase.
 */
public enum HashAlgorithm {

    SHA256("SHA-256", 64),
    SHA1("SHA-1", 40),
    BLAKE3("BLAKE3", 64);

    private final String jcaName;
    private final int hexLength;

    HashAlgorithm(String jcaName, int hexLength) {
        this.jcaName = jcaName;
        this.hexLength = hexLength;
    }

    /** The {@link java.security.MessageDigest} algorithm name, where applicable. */
    public String jcaName() {
        return jcaName;
    }

    public int hexLength() {
        return hexLength;
    }

    /** True if {@code value} is a hex string of the exact length for this algorithm. */
    public boolean isValidHex(String value) {
        return value != null && value.length() == hexLength && Hashes.isHex(value);
    }
}
