package com.pathongko.skein.app.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.IndexRef;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.manifest.RootManifest;
import com.pathongko.skein.core.sync.port.ManifestResolver;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.RangeResponse;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the split manifest over HTTP: {@code GET /manifest/root} then one
 * {@code GET /manifest/index/{CATEGORY}} per index the root points to, decoding
 * each with {@link JsonManifestCodec}. Network/HTTP failures are surfaced as typed
 * {@link SkeinException}s so the orchestrator reports them cleanly.
 */
public final class HttpManifestResolver implements ManifestResolver {

    private final HttpRangeClient client;
    private final JsonManifestCodec codec;
    private final String baseUrl;

    public HttpManifestResolver(HttpRangeClient client, JsonManifestCodec codec, String baseUrl) {
        this.client = Preconditions.requireNonNull(client, "client");
        this.codec = Preconditions.requireNonNull(codec, "codec");
        this.baseUrl = trimTrailingSlash(Preconditions.requireNonBlank(baseUrl, "baseUrl"));
    }

    @Override
    public ResolvedManifest resolve() {
        RootManifest root = codec.decodeRoot(fetch(baseUrl + "/manifest/root"));
        List<CategoryIndex> indices = new ArrayList<>();
        for (IndexRef ref : root.indices()) {
            byte[] indexBytes = fetch(baseUrl + "/manifest/index/" + ref.category().name());
            indices.add(codec.decodeIndex(indexBytes));
        }
        return new ResolvedManifest(root, indices);
    }

    private byte[] fetch(String url) {
        try (RangeResponse response = client.open(URI.create(url), 0)) {
            if (response.statusCode() != 200) {
                throw new SkeinException(ErrorCode.MANIFEST_UNAVAILABLE, Recoverability.NEEDS_ADMIN,
                        "manifest fetch returned HTTP " + response.statusCode()).with("url", url);
            }
            return response.body().readAllBytes();
        } catch (IOException e) {
            throw new SkeinException(ErrorCode.MANIFEST_UNAVAILABLE, Recoverability.RETRYABLE,
                    "manifest fetch failed: " + url, e).with("url", url);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
