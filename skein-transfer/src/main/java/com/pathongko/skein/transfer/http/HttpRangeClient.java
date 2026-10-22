package com.pathongko.skein.transfer.http;

import java.io.IOException;
import java.net.URI;

/**
 * A minimal HTTP client that can request a byte range. A port: the production
 * adapter wraps the JDK {@code java.net.http.HttpClient}, while tests supply a
 * fake that can simulate mid-stream drops, missing range support, and corrupt
 * bodies — none of which need a real server.
 */
public interface HttpRangeClient {

    /**
     * Open a GET for {@code uri} requesting bytes from {@code fromOffset} onward
     * (a full GET when {@code fromOffset == 0}).
     */
    RangeResponse open(URI uri, long fromOffset) throws IOException;
}
