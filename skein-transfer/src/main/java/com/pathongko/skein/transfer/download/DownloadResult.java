package com.pathongko.skein.transfer.download;

import com.pathongko.skein.core.domain.FileHash;

/**
 * The outcome of a successful download: the verified hash of the file on disk and
 * how many bytes were transferred over the network (which is less than the file
 * size when a resume skipped an already-downloaded prefix).
 */
public record DownloadResult(FileHash verifiedHash, long bytesTransferred) {
}
