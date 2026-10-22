package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.storage.file.DefaultFileManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one client sync session from a server-issued {@link SessionGrantS2C}:
 * fast-path check against the cached root hash, otherwise build and run the
 * orchestrator, republishing events to a listener. Never throws.
 */
public final class SkeinClientSession {

    private static final Duration QUARANTINE_RETENTION = Duration.ofDays(30);

    private final ClientPlatform platform;
    private final SyncProgressListener listener;

    public SkeinClientSession(ClientPlatform platform, SyncProgressListener listener) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.listener = Preconditions.requireNonNull(listener, "listener");
    }

    public SyncOutcome run(SessionGrantS2C grant) {
        Preconditions.requireNonNull(grant, "grant");
        if (!grant.granted()) {
            return new SyncOutcome.Failed(ErrorCode.ACCESS_DENIED, Recoverability.NEEDS_ADMIN, grant.deniedReason());
        }
        Optional<String> token = grant.sessionToken().isBlank()
                ? Optional.empty() : Optional.of(grant.sessionToken());
        return run(grant.transferEndpoint(), token, Optional.of(grant.rootManifestHash()));
    }

    /**
     * Sync directly from a resolved endpoint, with no play-phase grant. Used by
     * the disconnect-driven flow, which discovers the endpoint itself. When
     * {@code expectedRootHash} is present it enables the up-to-date fast path and
     * is cached on success; when absent, the diff runs unconditionally and the
     * cache is left untouched.
     */
    public SyncOutcome run(String transferEndpoint, Optional<String> token,
                           Optional<String> expectedRootHash) {
        Preconditions.requireNonBlank(transferEndpoint, "transferEndpoint");
        Preconditions.requireNonNull(token, "token");
        Preconditions.requireNonNull(expectedRootHash, "expectedRootHash");

        PlatformPaths paths = platform.paths();
        new DefaultFileManager(paths.gameDir()).pruneQuarantine(Instant.now(), QUARANTINE_RETENTION);

        RootHashCache rootHashCache = new RootHashCache(paths.skeinDir());

        if (expectedRootHash.isPresent()
                && rootHashCache.read().filter(h -> h.equals(expectedRootHash.get())).isPresent()) {
            SyncOutcome upToDate = new SyncOutcome.UpToDate();
            try {
                listener.onEvent(new SyncEvent.SyncFinished(upToDate));
            } catch (RuntimeException ignored) {
                // listener is display code; its failure must not break the never-throws contract
            }
            return upToDate;
        }

        InstalledPathsCache installedCache = new InstalledPathsCache(paths.skeinDir());
        Set<RelativePath> previouslyManaged = installedCache.read();

        EventBus events = new SynchronousEventBus();
        events.subscribe(listener::onEvent);
        // Captures the manifest's desired path set, and the subset of it this run
        // actually planned to fetch, as of this run's diff (published before any
        // download/apply I/O happens). Written back below — combined with
        // previouslyManaged — only when the outcome is a success.
        //
        // The new ledger is (previouslyManaged ∪ fetchedPaths) ∩ desiredPaths, NOT
        // plain desiredPaths: desiredPaths includes every path the manifest wants,
        // including ones that were already correct on disk and so were classified
        // `unchanged` by the diff engine — Skein never fetched or committed those,
        // so writing them into the ledger would let a later sync quarantine a file
        // Skein never actually installed (e.g. a player's pre-existing, byte-
        // identical mod). fetchedPaths, by contrast, only contains paths this run
        // genuinely planned to fetch (ADD/UPDATE) — and DefaultDownloadService/
        // DefaultApplyService's all-or-throw structure guarantees "success" only
        // happens when every one of those was actually committed, so fetchedPaths
        // accurately reflects what Skein wrote this round. Carrying forward
        // previouslyManaged lets a file fetched in an earlier round stay tracked in
        // later rounds where it's merely `unchanged` (not re-fetched); intersecting
        // with desiredPaths drops anything the manifest no longer wants. Note this
        // no longer depends on "every desired entry ends up on disk after success" —
        // it only depends on "every planned fetch was committed", so it stays
        // correct even if FileEntry's currently-unused SyncPolicy(required=false)
        // were ever honored to let the diff engine skip an optional fetch. A future
        // DownloadService/ApplyService implementation that allows partial completion
        // without throwing would break the "every planned fetch was committed"
        // premise — re-check this comment if either port grows a new implementation.
        AtomicReference<Set<RelativePath>> desiredPaths = new AtomicReference<>(Set.of());
        AtomicReference<Set<RelativePath>> fetchedPaths = new AtomicReference<>(Set.of());
        events.subscribe(event -> {
            if (event instanceof SyncEvent.DiffComputed diffComputed) {
                desiredPaths.set(diffComputed.desiredPaths());
                fetchedPaths.set(diffComputed.fetchedPaths());
            }
        });

        SyncOrchestrator orchestrator = SyncComponents.forSync(
                transferEndpoint, paths.gameDir(), token, events, previouslyManaged);

        SyncOutcome outcome = orchestrator.run();
        if (outcome.isSuccess()) {
            Set<RelativePath> newLedger = new HashSet<>(previouslyManaged);
            newLedger.addAll(fetchedPaths.get());
            newLedger.retainAll(desiredPaths.get());
            installedCache.write(Set.copyOf(newLedger));
            if (expectedRootHash.isPresent()) {
                try {
                    rootHashCache.write(expectedRootHash.get());
                } catch (IllegalArgumentException ignored) {
                    // best-effort cache; a blank hash from an otherwise-successful sync
                    // just costs a redundant diff next time, not a session failure
                }
            }
        }
        return outcome;
    }
}
