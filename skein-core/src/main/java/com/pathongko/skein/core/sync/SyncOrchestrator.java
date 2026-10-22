package com.pathongko.skein.core.sync;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.diff.DiffOptions;
import com.pathongko.skein.core.diff.LocalIndex;
import com.pathongko.skein.core.diff.PlannedDeletion;
import com.pathongko.skein.core.diff.PlannedFetch;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.port.DiffEngine;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.core.sync.port.LocalStateProvider;
import com.pathongko.skein.core.sync.port.ManifestResolver;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drives one sync session as a state machine, in order: resolve manifest, diff,
 * download, apply. It is the single error boundary — no exception escapes; every
 * path returns a typed {@link SyncOutcome}. It is deliberately sequential: the
 * parallelism and I/O live behind the service ports, so this class is a pure,
 * deterministic decision-maker that can be tested end-to-end with fakes.
 *
 * <p>Not thread-safe; one orchestrator drives one session (design doc, ADR-04).
 */
public final class SyncOrchestrator {

    private final ManifestResolver manifestResolver;
    private final LocalStateProvider localState;
    private final DiffEngine diffEngine;
    private final DownloadService downloadService;
    private final ApplyService applyService;
    private final EventBus events;
    private final DiffOptions diffOptions;
    private final CancellationToken cancellation;

    private SyncState state = SyncState.IDLE;

    public SyncOrchestrator(ManifestResolver manifestResolver, LocalStateProvider localState,
                            DiffEngine diffEngine, DownloadService downloadService,
                            ApplyService applyService, EventBus events,
                            DiffOptions diffOptions, CancellationToken cancellation) {
        this.manifestResolver = Preconditions.requireNonNull(manifestResolver, "manifestResolver");
        this.localState = Preconditions.requireNonNull(localState, "localState");
        this.diffEngine = Preconditions.requireNonNull(diffEngine, "diffEngine");
        this.downloadService = Preconditions.requireNonNull(downloadService, "downloadService");
        this.applyService = Preconditions.requireNonNull(applyService, "applyService");
        this.events = Preconditions.requireNonNull(events, "events");
        this.diffOptions = Preconditions.requireNonNull(diffOptions, "diffOptions");
        this.cancellation = Preconditions.requireNonNull(cancellation, "cancellation");
    }

    public SyncState currentState() {
        return state;
    }

    /** Run the session to a terminal outcome. Never throws. */
    public SyncOutcome run() {
        try {
            return finish(execute());
        } catch (Cancelled cancelled) {
            transitionTo(SyncState.CANCELLED);
            return finish(new SyncOutcome.Cancelled());
        } catch (SkeinException e) {
            transitionTo(SyncState.FAILED);
            return finish(new SyncOutcome.Failed(e.code(), e.recoverability(), e.getMessage()));
        } catch (RuntimeException e) {
            transitionTo(SyncState.FAILED);
            return finish(new SyncOutcome.Failed(ErrorCode.UNKNOWN, Recoverability.FATAL, e.toString()));
        }
    }

    private SyncOutcome execute() {
        checkCancelled();
        transitionTo(SyncState.RESOLVING_MANIFEST);
        ResolvedManifest manifest = manifestResolver.resolve();
        events.publish(new SyncEvent.ManifestResolved(
                manifest.root().packId(), manifest.root().packVersion()));

        checkCancelled();
        transitionTo(SyncState.DIFFING);
        LocalIndex local = localState.capture(manifest);
        SyncPlan plan = diffEngine.diff(manifest, local, diffOptions);
        Set<RelativePath> fetchedPaths = plan.fetches().stream()
                .map(f -> f.entry().path())
                .collect(Collectors.toUnmodifiableSet());
        events.publish(new SyncEvent.DiffComputed(
                plan.fetches().size(), plan.deletions().size(), plan.bytesToDownload(),
                manifest.desiredByPath().keySet(), fetchedPaths));

        if (plan.isEmpty()) {
            transitionTo(SyncState.DONE);
            return new SyncOutcome.UpToDate();
        }

        checkCancelled();
        transitionTo(SyncState.DOWNLOADING);
        long total = plan.bytesToDownload();
        downloadService.execute(plan, (done, t) -> events.publish(new SyncEvent.ProgressUpdated(done, total)));

        checkCancelled();
        transitionTo(SyncState.APPLYING);
        applyService.apply(plan);

        transitionTo(SyncState.DONE);
        return requiresRestart(plan)
                ? new SyncOutcome.RestartRequired()
                : new SyncOutcome.Applied();
    }

    private SyncOutcome finish(SyncOutcome outcome) {
        events.publish(new SyncEvent.SyncFinished(outcome));
        return outcome;
    }

    private void transitionTo(SyncState next) {
        SyncState previous = this.state;
        this.state = next;
        events.publish(new SyncEvent.StateChanged(previous, next));
    }

    private void checkCancelled() {
        if (cancellation.isCancelled()) {
            throw new Cancelled();
        }
    }

    /** Applying mods means the JVM must restart to load them (design doc, Part 3). */
    private static boolean requiresRestart(SyncPlan plan) {
        boolean modFetched = plan.fetches().stream()
                .map(PlannedFetch::entry)
                .anyMatch(e -> e.category() == FileCategory.MOD);
        boolean modDeleted = plan.deletions().stream()
                .map(PlannedDeletion::path)
                .anyMatch(p -> p.topSegment().equals("mods"));
        return modFetched || modDeleted;
    }

    /** Internal signal for cooperative cancellation; never leaves this class. */
    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Cancelled() {
            super(null, null, false, false);
        }
    }
}
