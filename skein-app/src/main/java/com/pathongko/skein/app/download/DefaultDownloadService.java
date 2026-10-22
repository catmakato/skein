package com.pathongko.skein.app.download;

import com.pathongko.skein.core.diff.PlannedFetch;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.port.FileManager;
import com.pathongko.skein.core.sync.ProgressCallback;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.transfer.download.FileDownloader;
import com.pathongko.skein.transfer.download.ProgressListener;
import com.pathongko.skein.transfer.download.TransferException;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements the orchestrator's {@link DownloadService} by driving the
 * {@link FileDownloader} for every planned fetch, staging each file via the
 * {@link FileManager}. Byte progress is aggregated across files and forwarded to
 * the orchestrator. Downloader failures ({@link TransferException}) are wrapped
 * into the core's typed {@link SkeinException} as they cross back into the engine.
 *
 * <p>Currently sequential; a parallel coordinator (bounded pool, per-host limit)
 * is a drop-in replacement behind this same port (design doc, Part 4).
 */
public final class DefaultDownloadService implements DownloadService {

    private final FileDownloader downloader;
    private final FileManager fileManager;

    public DefaultDownloadService(FileDownloader downloader, FileManager fileManager) {
        this.downloader = Preconditions.requireNonNull(downloader, "downloader");
        this.fileManager = Preconditions.requireNonNull(fileManager, "fileManager");
    }

    @Override
    public void execute(SyncPlan plan, ProgressCallback progress) {
        long total = plan.bytesToDownload();
        AtomicLong done = new AtomicLong();
        ProgressListener listener = delta -> progress.onProgress(done.addAndGet(delta), total);

        for (PlannedFetch fetch : plan.fetches()) {
            Path staging = fileManager.stagingFileFor(fetch.entry().path());
            try {
                downloader.download(fetch.entry(), staging, listener);
            } catch (TransferException e) {
                throw wrap(e, fetch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkeinException(ErrorCode.DOWNLOAD_FAILED, Recoverability.RETRYABLE,
                        "download interrupted", e).with("path", fetch.entry().path().value());
            }
        }
    }

    private static SkeinException wrap(TransferException e, PlannedFetch fetch) {
        ErrorCode code;
        Recoverability recoverability;
        switch (e.kind()) {
            case INTEGRITY -> {
                code = ErrorCode.HASH_MISMATCH;
                recoverability = Recoverability.RETRYABLE;
            }
            case FATAL_LOCAL -> {
                code = ErrorCode.LOCAL_IO;
                recoverability = Recoverability.NEEDS_USER;
            }
            case PERMANENT -> {
                code = ErrorCode.DOWNLOAD_FAILED;
                recoverability = Recoverability.NEEDS_ADMIN;
            }
            default -> {
                code = ErrorCode.DOWNLOAD_FAILED;
                recoverability = Recoverability.RETRYABLE;
            }
        }
        return new SkeinException(code, recoverability, e.getMessage(), e)
                .with("path", fetch.entry().path().value());
    }
}
