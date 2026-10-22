package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.JdkHttpRangeClient;
import com.pathongko.skein.transfer.http.RangeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Cheap check that a Skein HTTP server lives at a base URL: fetch
 * {@code /manifest/root} and confirm it decodes as a root manifest. Used to
 * validate an auto-derived endpoint before syncing, and to validate a URL the
 * player typed manually. Never throws — a probe failure is just {@code false}.
 */
public final class EndpointProbe {

    private final Duration timeout;
    private final JsonManifestCodec codec = new JsonManifestCodec();

    public EndpointProbe(Duration timeout) {
        this.timeout = Preconditions.requireNonNull(timeout, "timeout");
    }

    public boolean probe(String baseUrl, Optional<String> token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = trimTrailingSlash(baseUrl) + "/manifest/root";
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), timeout, () -> token);
        try (RangeResponse response = client.open(URI.create(url), 0)) {
            if (response.statusCode() != 200) {
                return false;
            }
            codec.decodeRoot(response.body().readAllBytes());
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
