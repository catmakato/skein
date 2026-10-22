package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pathongko.skein.core.domain.RelativePath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledPathsCacheTest {

    @Test
    void emptyWhenNeverWritten(@TempDir Path dir) {
        assertThat(new InstalledPathsCache(dir.resolve("skein")).read()).isEmpty();
    }

    @Test
    void roundTrips(@TempDir Path dir) {
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));
        Set<RelativePath> paths = Set.of(new RelativePath("mods/a.jar"), new RelativePath("config/x.toml"));

        cache.write(paths);

        assertThat(cache.read()).isEqualTo(paths);
    }

    @Test
    void emptySetRoundTrips(@TempDir Path dir) {
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));

        cache.write(Set.of());

        assertThat(cache.read()).isEmpty();
    }

    @Test
    void unversionedFileIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        // Predates this feature (or hand-created): no "# skein-ledger v1" marker line.
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.writeString(skein.resolve("installed-paths.txt"), "mods/a.jar\nmods/b.jar\n");

        assertThat(new InstalledPathsCache(skein).read()).isEmpty();
    }

    @Test
    void malformedContentIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        // Has *a* first line, but it's not valid UTF-8 text at all — a different
        // failure mode than "unversioned" even though the fallback is the same.
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.write(skein.resolve("installed-paths.txt"), new byte[]{(byte) 0xFF, 0, 1, 2});

        assertThatCode(() -> new InstalledPathsCache(skein).read()).doesNotThrowAnyException();
        assertThat(new InstalledPathsCache(skein).read()).isEmpty();
    }

    @Test
    void writeWithAnEmbeddedNewlineDoesNotCorruptTheFile(@TempDir Path dir) {
        // A RelativePath containing "\n" would, if written naively, inject an extra
        // line into this one-path-per-line file -- smuggling in an unintended
        // "mods/injected.jar" entry that was never actually part of the set passed
        // to write(). The whole write must be aborted instead.
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));
        Set<RelativePath> unsafe = Set.of(new RelativePath("mods/evil\nmods/injected.jar"));

        cache.write(unsafe);

        // Fails safe: no file was written at all (missing file reads as empty), and
        // in particular no "mods/injected.jar" entry was smuggled in.
        assertThat(cache.read()).isEmpty();
    }

    @Test
    void writeWithAnEmbeddedCarriageReturnDoesNotCorruptTheFile(@TempDir Path dir) {
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));
        Set<RelativePath> unsafe = Set.of(new RelativePath("mods/evil\rmods/injected.jar"));

        cache.write(unsafe);

        assertThat(cache.read()).isEmpty();
    }

    @Test
    void writeWithAnEmbeddedNewlineDoesNotClobberAPreviouslyWrittenLedger(@TempDir Path dir) {
        // The safe-write contract is "don't write anything this round" -- a
        // previously-written good ledger must survive an attempt to write an unsafe
        // set over it, not be replaced by an empty/partial file.
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));
        Set<RelativePath> good = Set.of(new RelativePath("mods/a.jar"));
        cache.write(good);

        Set<RelativePath> unsafe = Set.of(
                new RelativePath("mods/a.jar"), new RelativePath("mods/evil\nmods/injected.jar"));
        cache.write(unsafe);

        assertThat(cache.read()).isEqualTo(good);
    }
}
