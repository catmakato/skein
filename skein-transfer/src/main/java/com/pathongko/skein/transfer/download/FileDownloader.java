package com.pathongko.skein.transfer.download;

import com.pathongko.skein.core.domain.FileEntry;

import java.nio.file.Path;

/**
 * Downloads one file to a staging path, resuming and retrying as needed, and
 * verifies its hash before returning. The caller (the sync orchestrator) then
 * hands the staging file to the {@code FileManager} for an atomic commit.
 */
public interface FileDownloader {

    /**
     * @param entry       the file to fetch (hash, size, mirror sources)
     * @param stagingFile where the bytes are written; a leftover partial here is
     *                    resumed from
     * @param listener    receives byte-progress updates
     * @return the verified result
     * @throws TransferException  if the download ultimately fails
     * @throws InterruptedException if interrupted while waiting to retry
     */
    DownloadResult download(FileEntry entry, Path stagingFile, ProgressListener listener)
            throws InterruptedException;
}
