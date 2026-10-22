package com.pathongko.skein.bootstrap;

import com.pathongko.skein.app.apply.DefaultApplyService;
import com.pathongko.skein.app.download.DefaultDownloadService;
import com.pathongko.skein.app.local.FileSystemLocalStateProvider;
import com.pathongko.skein.app.manifest.HttpManifestResolver;
import com.pathongko.skein.core.diff.DefaultDiffEngine;
import com.pathongko.skein.core.diff.DiffOptions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.FileManager;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.sync.CancellationToken;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.core.sync.port.ManifestResolver;
import com.pathongko.skein.storage.file.DefaultFileManager;
import com.pathongko.skein.transfer.download.DefaultFileDownloader;
import com.pathongko.skein.transfer.download.FileDownloader;
import com.pathongko.skein.transfer.download.Sleeper;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.JdkHttpRangeClient;
import com.pathongko.skein.transfer.retry.DefaultRetryPolicy;
import com.pathongko.skein.transfer.retry.ExponentialBackoff;
import com.pathongko.skein.transfer.retry.Jitter;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Assembles the shared sync engine from its parts. The single composition root. */
public final class SyncComponents {

    public static final Map<FileCategory, String> CATEGORY_DIRS = categoryDirs();

    /** Top-level dirs the local scan walks to detect files to add/update. */
    private static final java.util.Set<String> SCANNED_TOP_SEGMENTS =
            java.util.Set.copyOf(CATEGORY_DIRS.values());

    private SyncComponents() {
    }

    public static SyncOrchestrator forSync(String endpoint, Path targetDir, Optional<String> token,
                                           EventBus events, Set<RelativePath> previouslyManaged) {
        HashService hash = new MessageDigestHashService();
        JsonManifestCodec codec = new JsonManifestCodec();
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(60), () -> token);

        ManifestResolver manifests = new HttpManifestResolver(client, codec, endpoint);
        // Scan every managed category dir so ADD/UPDATE are detected; the scanned
        // segment set is independent of the deletion policy below.
        FileSystemLocalStateProvider localState = new FileSystemLocalStateProvider(
                targetDir, hash, HashAlgorithm.SHA256, SCANNED_TOP_SEGMENTS);
        FileManager fileManager = new DefaultFileManager(targetDir);
        FileDownloader downloader = new DefaultFileDownloader(client, hash,
                new DefaultRetryPolicy(4, new ExponentialBackoff(
                        Duration.ofMillis(500), Duration.ofSeconds(20), Jitter.full(Math::random))),
                Sleeper.real());
        DownloadService downloadService = new DefaultDownloadService(downloader, fileManager);
        ApplyService applyService = new DefaultApplyService(fileManager);

        // Ledger-gated: quarantine an extra file only when it's in previouslyManaged
        // (paths Skein itself installed as of the last successful sync — see
        // SkeinClientSession, which reads/writes the ledger around this call). A
        // file the player added is never in that set, so it's never touched. This
        // closes the gap plain additiveOnly() left: a mod dropped from the pack, or
        // renamed between versions, now gets cleaned up (design doc,
        // docs/superpowers/specs/2026-08-09-install-ledger-design.md).
        return new SyncOrchestrator(manifests, localState, new DefaultDiffEngine(),
                downloadService, applyService, events, DiffOptions.safe(previouslyManaged),
                CancellationToken.none());
    }

    private static Map<FileCategory, String> categoryDirs() {
        Map<FileCategory, String> map = new EnumMap<>(FileCategory.class);
        map.put(FileCategory.MOD, "mods");
        map.put(FileCategory.CONFIG, "config");
        map.put(FileCategory.RESOURCEPACK, "resourcepacks");
        map.put(FileCategory.SHADERPACK, "shaderpacks");
        map.put(FileCategory.DATAPACK, "datapacks");
        return map;
    }
}
