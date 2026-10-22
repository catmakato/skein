package com.pathongko.skein.transfer.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * The response to a ranged GET. {@code statusCode} follows HTTP semantics:
 * 200 = whole body (server ignored the range), 206 = partial (resume honoured),
 * 416 = range not satisfiable, others = error. The body stream is owned by the
 * caller and closed via {@link #close()}.
 */
public record RangeResponse(int statusCode, long contentLength, InputStream body) implements Closeable {

    public boolean isPartial() {
        return statusCode == 206;
    }

    public boolean isWholeBody() {
        return statusCode == 200;
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
