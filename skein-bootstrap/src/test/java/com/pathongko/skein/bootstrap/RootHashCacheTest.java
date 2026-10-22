package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootHashCacheTest {
    @Test
    void emptyWhenAbsent(@TempDir Path dir) {
        assertThat(new RootHashCache(dir.resolve("skein")).read()).isEmpty();
    }

    @Test
    void roundTrips(@TempDir Path dir) {
        RootHashCache cache = new RootHashCache(dir.resolve("skein"));
        cache.write("abc123");
        assertThat(cache.read()).contains("abc123");
    }

    @Test
    void readIsSafeOnGarbage(@TempDir Path dir) throws Exception {
        Path skein = dir.resolve("skein");
        java.nio.file.Files.createDirectories(skein);
        java.nio.file.Files.write(skein.resolve("root.hash"), new byte[]{0, 1, 2});
        // The property under test is that a corrupt cache file never throws.
        org.assertj.core.api.Assertions
                .assertThatCode(() -> new RootHashCache(skein).read())
                .doesNotThrowAnyException();
    }
}
