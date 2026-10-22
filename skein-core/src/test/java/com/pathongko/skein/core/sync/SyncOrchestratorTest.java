package com.pathongko.skein.core.sync;

import com.pathongko.skein.core.diff.DefaultDiffEngine;
import com.pathongko.skein.core.diff.DiffOptions;
import com.pathongko.skein.core.diff.LocalIndex;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.manifest.RootManifest;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.core.sync.port.LocalStateProvider;
import com.pathongko.skein.core.sync.port.ManifestResolver;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOrchestratorTest {

    private static FileHash hash(char c) {
        return FileHash.sha256(String.valueOf(c).repeat(64));
    }

    private static FileEntry file(String path, FileCategory category, FileHash hash) {
        return new FileEntry(new RelativePath(path), 100L, hash, category, SyncPolicy.mandatory(),
                Set.of(new DownloadSource(URI.create("http://h/" + hash.value()), 0)));
    }

    private static ResolvedManifest manifest(FileCategory category, FileEntry... entries) {
        RootManifest root = new RootManifest(1, "pack", PackVersion.of("1.0.0"),
                Instant.EPOCH, HashAlgorithm.SHA256, List.of(), Optional.empty());
        return new ResolvedManifest(root, List.of(new CategoryIndex(category, List.of(entries))));
    }

    private final List<SyncEvent> events = new ArrayList<>();
    private final EventBus bus = new SynchronousEventBus();
    private final RecordingDownload download = new RecordingDownload();
    private final RecordingApply apply = new RecordingApply();

    SyncOrchestratorTest() {
        bus.subscribe(events::add);
    }

    private SyncOrchestrator orchestrator(ResolvedManifest manifest, LocalIndex local, CancellationToken token) {
        return new SyncOrchestrator(() -> manifest, m -> local, new DefaultDiffEngine(),
                download, apply, bus, DiffOptions.defaults(), token);
    }

    private boolean reached(SyncState state) {
        return events.stream().anyMatch(e -> e instanceof SyncEvent.StateChanged sc && sc.to() == state);
    }

    @Test
    void upToDateSkipsDownloadAndApply() {
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));
        LocalIndex local = new LocalIndex(Map.of(new RelativePath("mods/a.jar"), hash('a')));

        SyncOutcome outcome = orchestrator(manifest(FileCategory.MOD, mod), local, CancellationToken.none()).run();

        assertThat(outcome).isInstanceOf(SyncOutcome.UpToDate.class);
        assertThat(download.called).isFalse();
        assertThat(events).last().isInstanceOf(SyncEvent.SyncFinished.class);
    }

    @Test
    void modChangeRequiresRestart() {
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));

        SyncOutcome outcome = orchestrator(manifest(FileCategory.MOD, mod), LocalIndex.empty(),
                CancellationToken.none()).run();

        assertThat(outcome).isInstanceOf(SyncOutcome.RestartRequired.class);
        assertThat(download.called).isTrue();
        assertThat(apply.called).isTrue();
        assertThat(reached(SyncState.DOWNLOADING)).isTrue();
        assertThat(events).anyMatch(e -> e instanceof SyncEvent.ProgressUpdated);
    }

    @Test
    void configOnlyChangeDoesNotRequireRestart() {
        FileEntry cfg = file("config/x.toml", FileCategory.CONFIG, hash('c'));

        SyncOutcome outcome = orchestrator(manifest(FileCategory.CONFIG, cfg), LocalIndex.empty(),
                CancellationToken.none()).run();

        assertThat(outcome).isInstanceOf(SyncOutcome.Applied.class);
    }

    @Test
    void downloadFailureBecomesTypedFailureAndSkipsApply() {
        download.toThrow = new SkeinException(ErrorCode.DOWNLOAD_FAILED, Recoverability.RETRYABLE, "boom");
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));

        SyncOutcome outcome = orchestrator(manifest(FileCategory.MOD, mod), LocalIndex.empty(),
                CancellationToken.none()).run();

        assertThat(outcome).isInstanceOfSatisfying(SyncOutcome.Failed.class,
                f -> assertThat(f.code()).isEqualTo(ErrorCode.DOWNLOAD_FAILED));
        assertThat(apply.called).isFalse();
        assertThat(reached(SyncState.FAILED)).isTrue();
    }

    @Test
    void cancellationBeforeDownloadYieldsCancelled() {
        int[] checks = {0};
        CancellationToken cancelOnThirdCheck = () -> ++checks[0] >= 3;
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));

        SyncOutcome outcome = orchestrator(manifest(FileCategory.MOD, mod), LocalIndex.empty(),
                cancelOnThirdCheck).run();

        assertThat(outcome).isInstanceOf(SyncOutcome.Cancelled.class);
        assertThat(download.called).isFalse();
        assertThat(reached(SyncState.CANCELLED)).isTrue();
    }

    @Test
    void diffComputedCarriesTheManifestsDesiredPaths() {
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));

        orchestrator(manifest(FileCategory.MOD, mod), LocalIndex.empty(), CancellationToken.none()).run();

        assertThat(events)
                .filteredOn(e -> e instanceof SyncEvent.DiffComputed)
                .singleElement()
                .satisfies(e -> assertThat(((SyncEvent.DiffComputed) e).desiredPaths())
                        .containsExactly(new RelativePath("mods/a.jar")));
    }

    @Test
    void diffComputedCarriesOnlyTheGenuinelyFetchedPathsSeparatelyFromDesiredPaths() {
        // "mods/a.jar" is missing locally, so it's fetched (ADD). "mods/b.jar" is
        // already present with a matching hash, so it's desired but never fetched
        // (classified `unchanged`). fetchedPaths must reflect only the former,
        // while desiredPaths still reflects both -- the two sets must genuinely
        // differ here, not be wired to the same value by mistake.
        FileEntry a = file("mods/a.jar", FileCategory.MOD, hash('a'));
        FileEntry b = file("mods/b.jar", FileCategory.MOD, hash('b'));
        LocalIndex local = new LocalIndex(Map.of(new RelativePath("mods/b.jar"), hash('b')));

        orchestrator(manifest(FileCategory.MOD, a, b), local, CancellationToken.none()).run();

        assertThat(events)
                .filteredOn(e -> e instanceof SyncEvent.DiffComputed)
                .singleElement()
                .satisfies(e -> {
                    SyncEvent.DiffComputed diffComputed = (SyncEvent.DiffComputed) e;
                    assertThat(diffComputed.desiredPaths())
                            .containsExactlyInAnyOrder(
                                    new RelativePath("mods/a.jar"), new RelativePath("mods/b.jar"));
                    assertThat(diffComputed.fetchedPaths())
                            .containsExactly(new RelativePath("mods/a.jar"));
                    assertThat(diffComputed.fetchedPaths()).isNotEqualTo(diffComputed.desiredPaths());
                });
    }

    private static final class RecordingDownload implements DownloadService {
        boolean called;
        RuntimeException toThrow;

        @Override
        public void execute(SyncPlan plan, ProgressCallback progress) {
            called = true;
            if (toThrow != null) {
                throw toThrow;
            }
            progress.onProgress(plan.bytesToDownload(), plan.bytesToDownload());
        }
    }

    private static final class RecordingApply implements ApplyService {
        boolean called;

        @Override
        public void apply(SyncPlan plan) {
            called = true;
        }
    }
}
