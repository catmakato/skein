package com.pathongko.skein.server.admin;

import com.pathongko.skein.common.util.Preconditions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A standalone HTTP server that hosts only the {@link AdminSurface} — used in
 * control-panel mode, where the admin dashboard runs on its own port and each
 * managed pack is served on its own separate port by the control plane.
 */
public final class AdminHttpServer {

    private final AdminSurface admin;
    private final InetSocketAddress bindAddress;
    private HttpServer server;
    private ExecutorService executor;

    public AdminHttpServer(AdminSurface admin, InetSocketAddress bindAddress) {
        this.admin = Preconditions.requireNonNull(admin, "admin");
        this.bindAddress = Preconditions.requireNonNull(bindAddress, "bindAddress");
    }

    public int start() throws IOException {
        server = HttpServer.create(bindAddress, 0);
        // See SkeinHttpServer: without an executor, one stalled request blocks
        // every other client on HttpServer's single dispatcher thread.
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
            if (path.equals("/")) {
                // Convenience: send the root straight to the dashboard.
                exchange.getResponseHeaders().add("Location", "/admin");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (admin.handles(path)) {
                admin.handle(exchange);
            } else {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (RuntimeException e) {
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }
}
