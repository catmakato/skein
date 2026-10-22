package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Preconditions;

import java.net.URI;

/**
 * One place a file's bytes can be fetched from. A file may have several sources
 * (mirrors); the downloader rotates between them on failure.
 */
public record DownloadSource(URI uri, int priority) {

    public DownloadSource {
        Preconditions.requireNonNull(uri, "uri");
    }

    public static DownloadSource of(URI uri) {
        return new DownloadSource(uri, 0);
    }
}
