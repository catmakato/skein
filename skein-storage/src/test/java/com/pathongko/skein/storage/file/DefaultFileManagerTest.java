package com.pathongko.skein.storage.file;

import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.port.CommitReceipt;
import com.pathongko.skein.core.port.FileManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFileManagerTest {

    @TempDir
    Path root;

    private final RelativePath rel = new RelativePath("mods/a.jar");

    @Test
    void commitAtomicallyPlacesTheStagedFile() throws Exception {
        FileManager fm = new DefaultFileManager(root);
        Files.writeString(fm.stagingFileFor(rel), "hello");

        CommitReceipt receipt = fm.commit(rel);

        assertThat(Files.readString(root.resolve("mods/a.jar"))).isEqualTo("hello");
        assertThat(receipt.quarantinedPrevious()).isEmpty();
    }

    @Test
    void replacingAFileQuarantinesTheOldOne() throws Exception {
        FileManager fm = new DefaultFileManager(root);
        Files.writeString(fm.stagingFileFor(rel), "old");
        fm.commit(rel);
        Files.writeString(fm.stagingFileFor(rel), "new");

        CommitReceipt receipt = fm.commit(rel);

        assertThat(Files.readString(root.resolve("mods/a.jar"))).isEqualTo("new");
        assertThat(receipt.quarantinedPrevious()).isPresent();
        assertThat(Files.readString(receipt.quarantinedPrevious().orElseThrow())).isEqualTo("old");
    }

    @Test
    void rollbackRestoresTheReplacedFile() throws Exception {
        FileManager fm = new DefaultFileManager(root);
        Files.writeString(fm.stagingFileFor(rel), "v1");
        fm.commit(rel);
        Files.writeString(fm.stagingFileFor(rel), "v2");
        CommitReceipt receipt = fm.commit(rel);

        fm.rollback(receipt);

        assertThat(Files.readString(root.resolve("mods/a.jar"))).isEqualTo("v1");
    }

    @Test
    void quarantineMovesLiveFileAside() throws Exception {
        FileManager fm = new DefaultFileManager(root);
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("mods/orphan.jar"), "x");

        Optional<Path> quarantined = fm.quarantine(new RelativePath("mods/orphan.jar"));

        assertThat(quarantined).isPresent();
        assertThat(Files.exists(root.resolve("mods/orphan.jar"))).isFalse();
    }

    @Test
    void stagingPathStaysUnderRoot() {
        FileManager fm = new DefaultFileManager(root);
        // Lexical check (java.nio Path.startsWith); AssertJ's Path.startsWith would
        // call toRealPath() and require the not-yet-created .part file to exist.
        assertThat(fm.stagingFileFor(new RelativePath("config/x.toml")).startsWith(root)).isTrue();
    }

    @Test
    void pruneQuarantineRemovesABatchOlderThanMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        long oldMillis = Instant.now().minus(Duration.ofDays(31)).toEpochMilli();
        Path oldBatch = root.resolve(".skein").resolve("quarantine").resolve(oldMillis + "-1");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("orphan.jar"), "old");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(oldBatch)).isFalse();
    }

    @Test
    void pruneQuarantineKeepsABatchWithinMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        long recentMillis = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        Path recentBatch = root.resolve(".skein").resolve("quarantine").resolve(recentMillis + "-1");
        Files.createDirectories(recentBatch);
        Files.writeString(recentBatch.resolve("orphan.jar"), "recent");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(recentBatch)).isTrue();
    }

    @Test
    void pruneQuarantineLeavesAnEntryThatDoesNotMatchTheBatchNamingPatternAlone() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Path notABatch = root.resolve(".skein").resolve("quarantine").resolve("not-a-batch");
        Files.createDirectories(notABatch);
        Files.writeString(notABatch.resolve("mystery.txt"), "leave me alone");

        fm.pruneQuarantine(Instant.now(), Duration.ZERO);

        assertThat(Files.exists(notABatch)).isTrue();
    }

    @Test
    void pruneQuarantineIsANoOpWhenQuarantineRootDoesNotExist() {
        DefaultFileManager fm = new DefaultFileManager(root);

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(root.resolve(".skein").resolve("quarantine"))).isFalse();
    }

    @Test
    void pruneQuarantineOnlyRemovesTheOldBatchWhenAYoungOneIsAlsoPresent() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        long youngMillis = Instant.now().minus(Duration.ofHours(1)).toEpochMilli();
        Path oldBatch = quarantineDir.resolve(oldMillis + "-1");
        Path youngBatch = quarantineDir.resolve(youngMillis + "-2");
        Files.createDirectories(oldBatch);
        Files.createDirectories(youngBatch);
        Files.writeString(oldBatch.resolve("old.jar"), "old");
        Files.writeString(youngBatch.resolve("young.jar"), "young");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(oldBatch)).isFalse();
        assertThat(Files.exists(youngBatch)).isTrue();
    }

    @Test
    void pruneQuarantineLeavesASymlinkedEntryAloneAndDoesNotFollowIt() throws Exception {
        Path outsideTarget = root.resolve("outside-target");
        Files.createDirectories(outsideTarget);
        Files.writeString(outsideTarget.resolve("secret.txt"), "keep-me");

        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        Files.createDirectories(quarantineDir);
        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        Path link = quarantineDir.resolve(oldMillis + "-1");
        try {
            Files.createSymbolicLink(link, outsideTarget);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlinks not supported/permitted in this environment: " + e);
            return;
        }

        DefaultFileManager fm = new DefaultFileManager(root);
        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.exists(outsideTarget.resolve("secret.txt"))).isTrue();
    }

    @Test
    void pruneQuarantineSkipsAnEntryWithAnUnparseableEpochPrefixButStillPrunesAGenuinelyOldBatch()
            throws Exception {
        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        // Shape-matches "\d+-\d+" but the leading number overflows Long.parseLong.
        Path overflowing = quarantineDir.resolve("99999999999999999999-1");
        Files.createDirectories(overflowing);
        Files.writeString(overflowing.resolve("weird.jar"), "weird");

        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        Path oldBatch = quarantineDir.resolve(oldMillis + "-2");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("old.jar"), "old");

        DefaultFileManager fm = new DefaultFileManager(root);
        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(overflowing)).isTrue();
        assertThat(Files.exists(oldBatch)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void pruneQuarantineDoesNotFollowADirectoryJunctionNestedInsideAnExpiredBatch() throws Exception {
        Path outsideTarget = root.resolve("outside-target");
        Files.createDirectories(outsideTarget);
        Files.writeString(outsideTarget.resolve("secret.txt"), "keep-me");

        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        Path oldBatch = quarantineDir.resolve(oldMillis + "-1");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("legit.jar"), "legit");
        Path junction = oldBatch.resolve("escape-link");

        // A Windows directory junction (unlike a real NTFS symlink) needs no
        // elevated privilege to create, and Files.isSymbolicLink(...) returns
        // false for one -- which is exactly the gap this test guards against.
        Process mklink = new ProcessBuilder(
                "cmd", "/c", "mklink", "/J", junction.toString(), outsideTarget.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = mklink.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished && mklink.exitValue() == 0,
                "could not create a directory junction in this environment");

        DefaultFileManager fm = new DefaultFileManager(root);
        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(oldBatch)).isFalse();
        assertThat(Files.exists(outsideTarget.resolve("secret.txt"))).isTrue();
    }

    @Test
    void pruneQuarantineKeepsABatchExactlyAtMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Instant now = Instant.now();
        long boundaryMillis = now.minus(Duration.ofDays(30)).toEpochMilli();
        Path boundaryBatch = root.resolve(".skein").resolve("quarantine").resolve(boundaryMillis + "-1");
        Files.createDirectories(boundaryBatch);
        Files.writeString(boundaryBatch.resolve("edge.jar"), "edge");

        fm.pruneQuarantine(now, Duration.ofDays(30));

        assertThat(Files.exists(boundaryBatch)).isTrue();
    }

    @Test
    void pruneQuarantineRemovesABatchOneMillisecondPastMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Instant now = Instant.now();
        long justPastMillis = now.minus(Duration.ofDays(30)).toEpochMilli() - 1;
        Path justPastBatch = root.resolve(".skein").resolve("quarantine").resolve(justPastMillis + "-1");
        Files.createDirectories(justPastBatch);
        Files.writeString(justPastBatch.resolve("edge.jar"), "edge");

        fm.pruneQuarantine(now, Duration.ofDays(30));

        assertThat(Files.exists(justPastBatch)).isFalse();
    }
}
