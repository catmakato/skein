package com.pathongko.skein.transfer.download;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.port.IncrementalHasher;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.RangeResponse;
import com.pathongko.skein.transfer.retry.FailureContext;
import com.pathongko.skein.transfer.retry.FailureKind;
import com.pathongko.skein.transfer.retry.RetryDecision;
import com.pathongko.skein.transfer.retry.RetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * The download engine for a single file. Ties together the pieces built earlier:
 * it resumes from a leftover partial (via a byte-range request), hashes the bytes
 * in a single pass as they are written, verifies against the manifest hash, and
 * on failure consults the {@link RetryPolicy} to decide whether to wait, discard
 * the partial, and/or rotate to a different mirror. All I/O is behind
 * {@link HttpRangeClient} so this whole flow is testable with a fake.
 */
public final class DefaultFileDownloader implements FileDownloader {

    private static final int DEFAULT_BUFFER = 64 * 1024;

    private final HttpRangeClient client;
    private final HashService hashService;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final int bufferSize;

    public DefaultFileDownloader(HttpRangeClient client, HashService hashService,
                                 RetryPolicy retryPolicy, Sleeper sleeper) {
        this(client, hashService, retryPolicy, sleeper, DEFAULT_BUFFER);
    }

    public DefaultFileDownloader(HttpRangeClient client, HashService hashService,
                                 RetryPolicy retryPolicy, Sleeper sleeper, int bufferSize) {
        this.client = Preconditions.requireNonNull(client, "client");
        this.hashService = Preconditions.requireNonNull(hashService, "hashService");
        this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Preconditions.requireNonNull(sleeper, "sleeper");
        Preconditions.checkArgument(bufferSize > 0, "bufferSize must be > 0");
        this.bufferSize = bufferSize;
    }

    @Override
    public DownloadResult download(FileEntry entry, Path stagingFile, ProgressListener listener)
            throws InterruptedException {
        List<DownloadSource> sources = entry.orderedSources();
        Preconditions.checkArgument(!sources.isEmpty(), "entry has no download sources");
        int otherMirrors = sources.size() - 1;

        int sourceIdx = 0;
        int attempt = 0;
        while (true) {
            attempt++;
            long existing = existingBytes(stagingFile);
            URI uri = sources.get(sourceIdx % sources.size()).uri();
            try {
                return attemptOnce(uri, entry, stagingFile, existing, listener);
            } catch (TransferException failure) {
                RetryDecision decision = retryPolicy.onFailure(
                        new FailureContext(attempt, failure.kind(), otherMirrors));
                if (!decision.shouldRetry()) {
                    throw failure;
                }
                if (decision.discardPartial()) {
                    deleteQuietly(stagingFile);
                }
                if (decision.rotateMirror()) {
                    sourceIdx++;
                }
                sleeper.sleep(decision.delay());
            }
        }
    }

    private DownloadResult attemptOnce(URI uri, FileEntry entry, Path staging, long existing,
                                       ProgressListener listener) {
        HashAlgorithm algo = entry.hash().algorithm();
        try (RangeResponse resp = client.open(uri, existing)) {
            Optional<FailureKind> statusFailure = StatusClassifier.classify(resp.statusCode());
            if (statusFailure.isPresent()) {
                throw new TransferException(statusFailure.get(), "HTTP " + resp.statusCode());
            }

            boolean resume = resp.isPartial() && existing > 0;
            IncrementalHasher hasher = hashService.newHasher(algo);
            OpenOption[] openOptions;
            if (resume) {
                seedHasherFromDisk(staging, hasher);
                openOptions = new OpenOption[]{
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND};
            } else {
                openOptions = new OpenOption[]{
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
            }

            long transferred = copy(resp.body(), staging, openOptions, hasher, listener);

            long sizeOnDisk = existingBytes(staging);
            long expected = entry.sizeBytes();
            if (sizeOnDisk < expected) {
                throw new TransferException(FailureKind.TRANSIENT,
                        "short read: " + sizeOnDisk + "/" + expected);
            }
            if (sizeOnDisk > expected) {
                throw new TransferException(FailureKind.RESUMABLE_RESET,
                        "overshoot: " + sizeOnDisk + "/" + expected);
            }

            FileHash actual = hasher.finish();
            if (!actual.matches(entry.hash())) {
                throw new TransferException(FailureKind.INTEGRITY,
                        "hash mismatch for " + entry.path());
            }
            return new DownloadResult(actual, transferred);
        } catch (IOException e) {
            // Failure opening the connection or closing the response.
            throw new TransferException(FailureKind.TRANSIENT, "connection failed: " + uri, e);
        }
    }

    private long copy(InputStream body, Path staging, OpenOption[] openOptions,
                      IncrementalHasher hasher, ProgressListener listener) {
        long transferred = 0;
        try (OutputStream out = Files.newOutputStream(staging, openOptions)) {
            byte[] buf = new byte[bufferSize];
            int n;
            while ((n = body.read(buf)) != -1) {
                out.write(buf, 0, n);
                hasher.update(buf, 0, n);
                transferred += n;
                listener.onBytes(n);
            }
            return transferred;
        } catch (IOException e) {
            // Bytes already written stay on disk so the next attempt can resume.
            throw new TransferException(FailureKind.TRANSIENT, "stream interrupted", e);
        }
    }

    private void seedHasherFromDisk(Path staging, IncrementalHasher hasher) {
        try (InputStream in = Files.newInputStream(staging)) {
            byte[] buf = new byte[bufferSize];
            int n;
            while ((n = in.read(buf)) != -1) {
                hasher.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new TransferException(FailureKind.FATAL_LOCAL, "cannot read partial file", e);
        }
    }

    private long existingBytes(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            throw new TransferException(FailureKind.FATAL_LOCAL, "cannot stat staging file", e);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new TransferException(FailureKind.FATAL_LOCAL, "cannot delete partial file", e);
        }
    }
}
