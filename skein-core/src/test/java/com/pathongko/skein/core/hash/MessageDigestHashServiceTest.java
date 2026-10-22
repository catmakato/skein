package com.pathongko.skein.core.hash;

import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.port.IncrementalHasher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageDigestHashServiceTest {

    private final HashService service = new MessageDigestHashService();

    @Test
    void matchesKnownSha256Vector() {
        // SHA-256("abc") is a well-known NIST test vector.
        FileHash hash = service.hash("abc".getBytes(StandardCharsets.UTF_8), HashAlgorithm.SHA256);
        assertThat(hash.value())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void incrementalHashingEqualsOneShot() {
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        FileHash oneShot = service.hash(data, HashAlgorithm.SHA256);

        IncrementalHasher hasher = service.newHasher(HashAlgorithm.SHA256);
        hasher.update(data, 0, 9);          // "the quick"
        hasher.update(data, 9, data.length - 9); // " brown fox"
        FileHash streamed = hasher.finish();

        assertThat(streamed.matches(oneShot)).isTrue();
    }
}
