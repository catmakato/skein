package com.pathongko.skein.core.diff;

import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.manifest.RootManifest;
import com.pathongko.skein.core.port.DiffEngine;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDiffEngineTest {

    private final DiffEngine engine = new DefaultDiffEngine();

    // Distinct 64-char SHA-256 hex values.
    private static FileHash hash(char c) {
        return FileHash.sha256(String.valueOf(c).repeat(64));
    }

    private static FileEntry mod(String path, FileHash hash) {
        return new FileEntry(
                new RelativePath(path), 100L, hash, FileCategory.MOD,
                SyncPolicy.mandatory(),
                Set.of(new com.pathongko.skein.core.domain.DownloadSource(URI.create("http://h/" + hash.value()), 0)));
    }

    private static ResolvedManifest manifest(FileEntry... entries) {
        RootManifest root = new RootManifest(
                RootManifest.CURRENT_SCHEMA_VERSION, "pack", PackVersion.of("1.0.0"),
                Instant.EPOCH, HashAlgorithm.SHA256, List.of(), Optional.empty());
        return new ResolvedManifest(root, List.of(new CategoryIndex(FileCategory.MOD, List.of(entries))));
    }

    @Test
    void classifiesAddUpdateAndUnchanged() {
        FileEntry a = mod("mods/a.jar", hash('a'));   // new
        FileEntry b = mod("mods/b.jar", hash('b'));   // changed
        FileEntry c = mod("mods/c.jar", hash('c'));   // same

        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/b.jar"), hash('9'),  // differs from manifest
                new RelativePath("mods/c.jar"), hash('c'))); // matches

        SyncPlan plan = engine.diff(manifest(a, b, c), local, DiffOptions.defaults());

        assertThat(plan.countOf(ChangeType.ADD)).isEqualTo(1);
        assertThat(plan.countOf(ChangeType.UPDATE)).isEqualTo(1);
        assertThat(plan.unchanged()).containsExactly(new RelativePath("mods/c.jar"));
        assertThat(plan.bytesToDownload()).isEqualTo(200L); // a + b, 100 each
        assertThat(plan.isEmpty()).isFalse();
    }

    @Test
    void identicalStateProducesEmptyPlan() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(new RelativePath("mods/a.jar"), hash('a')));

        assertThat(engine.diff(manifest(a), local, DiffOptions.defaults()).isEmpty()).isTrue();
    }

    @Test
    void extraFileUnderManagedDirIsPlannedForDeletion() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("mods/orphan.jar"), hash('f'))); // not in manifest

        SyncPlan plan = engine.diff(manifest(a), local, DiffOptions.defaults());

        assertThat(plan.deletions()).hasSize(1);
        assertThat(plan.deletions().get(0).path()).isEqualTo(new RelativePath("mods/orphan.jar"));
        assertThat(plan.deletions().get(0).policy()).isEqualTo(DeletionPolicy.QUARANTINE);
    }

    @Test
    void extraFileOutsideManagedDirIsLeftAlone() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("saves/world/level.dat"), hash('e'))); // player data

        SyncPlan plan = engine.diff(manifest(a), local, DiffOptions.defaults());

        assertThat(plan.deletions()).isEmpty();
    }

    @Test
    void additiveOnlyNeverDeletes() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(new RelativePath("mods/orphan.jar"), hash('f')));

        SyncPlan plan = engine.diff(manifest(a), local, DiffOptions.additiveOnly());

        assertThat(plan.deletions()).isEmpty();
        assertThat(plan.countOf(ChangeType.ADD)).isEqualTo(1);
    }

    @Test
    void extraFileInLedgerIsPlannedForDeletion() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("mods/orphan.jar"), hash('f')));

        DiffOptions options = DiffOptions.safe(Set.of(new RelativePath("mods/orphan.jar")));
        SyncPlan plan = engine.diff(manifest(a), local, options);

        assertThat(plan.deletions()).hasSize(1);
        assertThat(plan.deletions().get(0).path()).isEqualTo(new RelativePath("mods/orphan.jar"));
        assertThat(plan.deletions().get(0).policy()).isEqualTo(DeletionPolicy.QUARANTINE);
    }

    @Test
    void extraFileNotInLedgerIsKept() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("mods/playerMod.jar"), hash('f')));

        // The ledger tracks a.jar only — playerMod.jar was never installed by
        // Skein, so it must survive even though it's under a managed dir.
        DiffOptions options = DiffOptions.safe(Set.of(new RelativePath("mods/a.jar")));
        SyncPlan plan = engine.diff(manifest(a), local, options);

        assertThat(plan.deletions()).isEmpty();
    }
}
