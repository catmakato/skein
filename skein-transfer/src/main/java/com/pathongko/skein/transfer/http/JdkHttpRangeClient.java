package com.pathongko.skein.transfer.http;

import com.pathongko.skein.common.util.Preconditions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link HttpRangeClient} backed by the JDK's {@code java.net.http.HttpClient}
 * (so there is still no external dependency). Adds a {@code Range} header for
 * resumes and an optional {@code Authorization: Bearer} header for the session
 * token. The body is streamed, never buffered, so files of any size are fine.
 */
public final class JdkHttpRangeClient implements HttpRangeClient {

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Supplier<Optional<String>> bearerToken;

    public JdkHttpRangeClient(HttpClient httpClient, Duration requestTimeout,
                              Supplier<Optional<String>> bearerToken) {
        this.httpClient = Preconditions.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Preconditions.requireNonNull(requestTimeout, "requestTimeout");
        this.bearerToken = Preconditions.requireNonNull(bearerToken, "bearerToken");
    }

    /** A client with sane defaults and no auth token. */
    public static JdkHttpRangeClient create() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new JdkHttpRangeClient(client, Duration.ofSeconds(60), Optional::empty);
    }

    @Override
    public RangeResponse open(URI uri, long fromOffset) throws IOException {
        Preconditions.requireNonNull(uri, "uri");
        Preconditions.requireNonNegative(fromOffset, "fromOffset");

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout);
        if (fromOffset > 0) {
            request.header("Range", "bytes=" + fromOffset + "-");
        }
        bearerToken.get().ifPresent(token -> request.header("Authorization", "Bearer " + token));

        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new RangeResponse(response.statusCode(), contentLength, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while requesting " + uri, e);
        }
    }
}
