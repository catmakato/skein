package com.pathongko.skein.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackVersionTest {

    @Test
    void comparesSemverNumericallyNotLexically() {
        // "1.10" must be greater than "1.9" — the classic string-compare bug.
        assertThat(PackVersion.of("1.10.0")).isGreaterThan(PackVersion.of("1.9.0"));
    }

    @Test
    void comparesDateBasedVersions() {
        assertThat(PackVersion.of("2026.07.05-3")).isGreaterThan(PackVersion.of("2026.07.05-2"));
        assertThat(PackVersion.of("2026.07.06-1")).isGreaterThan(PackVersion.of("2026.07.05-9"));
    }

    @Test
    void equalWhenSegmentsMatch() {
        assertThat(PackVersion.of("1.2.0")).isEqualByComparingTo(PackVersion.of("1.2"));
    }
}
