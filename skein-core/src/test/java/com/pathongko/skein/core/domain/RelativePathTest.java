package com.pathongko.skein.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelativePathTest {

    @Test
    void acceptsNormalRelativePath() {
        RelativePath p = new RelativePath("mods/jei.jar");
        assertThat(p.topSegment()).isEqualTo("mods");
        assertThat(p.fileName()).isEqualTo("jei.jar");
    }

    @Test
    void normalizesBackslashesToForwardSlashes() {
        assertThat(new RelativePath("config\\sub\\a.toml").value()).isEqualTo("config/sub/a.toml");
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> new RelativePath("../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> new RelativePath("/etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsWindowsDriveOrScheme() {
        assertThatThrownBy(() -> new RelativePath("C:/Windows/system32"))
                .isInstanceOf(SecurityException.class);
    }
}
