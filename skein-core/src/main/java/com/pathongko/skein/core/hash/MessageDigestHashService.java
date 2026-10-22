package com.pathongko.skein.core.hash;

import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.port.IncrementalHasher;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@link HashService} backed by the JDK {@link MessageDigest}. Handles the
 * algorithms the JVM ships with (SHA-256, SHA-1). BLAKE3 is declared in the
 * domain for the future but has no JDK provider, so it is rejected here until a
 * dedicated implementation is added.
 */
public final class MessageDigestHashService implements HashService {

    @Override
    public FileHash hash(byte[] data, HashAlgorithm algorithm) {
        MessageDigest digest = newDigest(algorithm);
        return FileHash.of(algorithm, digest.digest(data));
    }

    @Override
    public IncrementalHasher newHasher(HashAlgorithm algorithm) {
        return new MessageDigestHasher(algorithm, newDigest(algorithm));
    }

    private static MessageDigest newDigest(HashAlgorithm algorithm) {
        try {
            return MessageDigest.getInstance(algorithm.jcaName());
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException(
                    "no JDK provider for " + algorithm + " (" + algorithm.jcaName() + ")", e);
        }
    }

    private static final class MessageDigestHasher implements IncrementalHasher {

        private final HashAlgorithm algorithm;
        private final MessageDigest digest;
        private boolean finished;

        MessageDigestHasher(HashAlgorithm algorithm, MessageDigest digest) {
            this.algorithm = algorithm;
            this.digest = digest;
        }

        @Override
        public void update(ByteBuffer chunk) {
            ensureOpen();
            digest.update(chunk);
        }

        @Override
        public void update(byte[] chunk, int offset, int length) {
            ensureOpen();
            digest.update(chunk, offset, length);
        }

        @Override
        public FileHash finish() {
            ensureOpen();
            finished = true;
            return FileHash.of(algorithm, digest.digest());
        }

        private void ensureOpen() {
            if (finished) {
                throw new IllegalStateException("hasher already finished");
            }
        }
    }
}
