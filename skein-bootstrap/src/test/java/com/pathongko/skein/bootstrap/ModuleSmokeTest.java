package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.HashAlgorithm;
import org.junit.jupiter.api.Test;

class ModuleSmokeTest {
    @Test
    void sharedModulesAreOnTheClasspath() {
        assertThat(HashAlgorithm.SHA256).isNotNull();
    }
}
