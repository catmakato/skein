package com.pathongko.skein.server.http;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.server.admin.AdminSurface;
import com.pathongko.skein.server.manifest.GeneratedPack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Serves a pack over HTTP:
 * <ul>
 *   <li>{@code GET /manifest/root} — the root manifest</li>
 *   <li>{@code GET /manifest/index/{CATEGORY}} — one category index</li>
 *   <li>{@code GET /files/{hash}} — file bytes, addressed by content hash, with
 *       {@code Range} support for resume</li>
 *   <li>{@code /admin[/...]} — the admin dashboard + API (when configured)</li>
 * </ul>
 * The pack is read through a {@link Supplier} so an admin regeneration is served
 * immediately. If a client token is configured, the manifest/file endpoints
 * require {@code Authorization: Bearer <token>} (constant-time compare); the admin
 * surface has its own, separate password auth.
 */
public final class SkeinHttpServer {

    private static final String INDEX_PREFIX = "/manifest/index/";
    private static final String FILES_PREFIX = "/files/";

    private final Supplier<GeneratedPack> packSupplier;
    private final Optional<String> token;
    private final Optional<AdminSurface> admin;
    private final InetSocketAddress bindAddress;
    private HttpServer server;
    private ExecutorService executor;

    /** Static pack, no admin surface. */
    public SkeinHttpServer(GeneratedPack pack, Optional<String> token, InetSocketAddress bindAddress) {
        this(() -> pack, token, Optional.empty(), bindAddress);
    }

    public SkeinHttpServer(Supplier<GeneratedPack> packSupplier, Optional<String> token,
                           Optional<AdminSurface> admin, InetSocketAddress bindAddress) {
        this.packSupplier = Preconditions.requireNonNull(packSupplier, "packSupplier");
        this.token = Preconditions.requireNonNull(token, "token");
        this.admin = Preconditions.requireNonNull(admin, "admin");
        this.bindAddress = Preconditions.requireNonNull(bindAddress, "bindAddress");
    }

    /** Start listening; returns the actual bound port. */
    public int start() throws IOException {
        try {
            server = HttpServer.create(bindAddress, 0);
        } catch (BindException occupied) {
            // Try nearby predictable ports first so operators can open/forward a
            // small fixed range. Use an ephemeral port only as a last resort.
            IOException lastFailure = occupied;
            int firstCandidate = bindAddress.getPort() == 65535 ? 1 : bindAddress.getPort() + 1;
            boolean bound = false;
            for (int offset = 0; offset < 32; offset++) {
                int candidate = ((firstCandidate + offset - 1) % 65535) + 1;
                try {
                    server = HttpServer.create(new InetSocketAddress(bindAddress.getAddress(), candidate), 0);
                    bound = true;
                    break;
                } catch (BindException alsoOccupied) {
                    lastFailure = alsoOccupied;
                }
            }
            if (!bound) {
                try {
                    server = HttpServer.create(new InetSocketAddress(bindAddress.getAddress(), 0), 0);
                } catch (IOException ephemeralFailure) {
                    ephemeralFailure.addSuppressed(lastFailure);
                    throw ephemeralFailure;
                }
            }
        }
        // Without an executor, HttpServer runs every exchange on its single
        // dispatcher thread; one stalled client (slow connection, alt-tabbed,
        // large file) then blocks every other client's request until it finishes.
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::route);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            // Admin surface handles its own (password) auth.
            if (admin.isPresent() && admin.get().handles(path)) {
                admin.get().handle(exchange);
                return;
            }

            if (!authorized(exchange)) {
                sendText(exchange, 401, "unauthorized");
                return;
            }
            if (path.equals("/manifest/root")) {
                sendBytes(exchange, 200, packSupplier.get().encodedRoot());
            } else if (path.startsWith(INDEX_PREFIX)) {
                serveIndex(exchange, path.substring(INDEX_PREFIX.length()));
            } else if (path.startsWith(FILES_PREFIX)) {
                serveFile(exchange, path.substring(FILES_PREFIX.length()));
            } else {
                sendText(exchange, 404, "not found");
            }
        } catch (RuntimeException e) {
            sendText(exchange, 500, "server error");
        } finally {
            exchange.close();
        }
    }

    private void serveIndex(HttpExchange exchange, String categoryName) throws IOException {
        FileCategory category;
        try {
            category = FileCategory.valueOf(categoryName);
        } catch (IllegalArgumentException e) {
            sendText(exchange, 404, "unknown category");
            return;
        }
        byte[] index = packSupplier.get().encodedIndices().get(category);
        if (index == null) {
            sendText(exchange, 404, "no index");
        } else {
            sendBytes(exchange, 200, index);
        }
    }

    private void serveFile(HttpExchange exchange, String hashHex) throws IOException {
        Path file = findByHash(hashHex);
        if (file == null) {
            sendText(exchange, 404, "no such file");
            return;
        }
        long total = Files.size(file);
        long offset = parseRangeOffset(exchange.getRequestHeaders().getFirst("Range"));
        if (offset < 0 || offset > total) {
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        int status = offset > 0 ? 206 : 200;
        long length = total - offset;
        exchange.sendResponseHeaders(status, length == 0 ? -1 : length);
        try (InputStream in = Files.newInputStream(file); OutputStream out = exchange.getResponseBody()) {
            in.skipNBytes(offset);
            in.transferTo(out);
        }
    }

    private Path findByHash(String hashHex) {
        for (Map.Entry<FileHash, Path> entry : packSupplier.get().contentByHash().entrySet()) {
            if (entry.getKey().value().equalsIgnoreCase(hashHex)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.isEmpty()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = token.get().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    private static long parseRangeOffset(String rangeHeader) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return 0;
        }
        String spec = rangeHeader.substring("bytes=".length());
        int dash = spec.indexOf('-');
        String start = dash >= 0 ? spec.substring(0, dash) : spec;
        try {
            return start.isEmpty() ? 0 : Long.parseLong(start.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        sendBytes(exchange, status, message.getBytes(StandardCharsets.UTF_8));
    }
}
