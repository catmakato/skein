package com.pathongko.skein.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileHashTest {

    private static final String VALID = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void acceptsValidSha256AndLowercasesIt() {
        FileHash hash = FileHash.sha256(VALID.toUpperCase());
        assertThat(hash.algorithm()).isEqualTo(HashAlgorithm.SHA256);
        assertThat(hash.value()).isEqualTo(VALID); // normalized to lower-case
    }

    @Test
    void matchIsCaseInsensitiveAndAlgorithmAware() {
        assertThat(FileHash.sha256(VALID).matches(FileHash.sha256(VALID.toUpperCase()))).isTrue();
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> FileHash.sha256("abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonHex() {
        String badButRightLength = "z".repeat(64);
        assertThatThrownBy(() -> FileHash.sha256(badButRightLength))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
