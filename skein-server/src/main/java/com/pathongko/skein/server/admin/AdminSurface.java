package com.pathongko.skein.server.admin;

import com.pathongko.skein.common.json.Json;
import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.server.manage.ControlPlane;
import com.pathongko.skein.server.manage.ManagedPack;
import com.pathongko.skein.server.manage.ServerNode;
import com.pathongko.skein.server.manifest.GeneratedPack;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The multi-server admin web surface: serves the dashboard and a JSON API that
 * manages every pack in the {@link ControlPlane} (create/remove servers, and
 * per-server list/upload/delete/version/regenerate). Guarded by an admin password
 * — login returns an in-memory session token sent as {@code X-Admin-Token} on
 * every later call. The admin password is separate from the client tokens, so a
 * player never gets management rights.
 */
public final class AdminSurface {

    private final ControlPlane controlPlane;
    private final byte[] password;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public AdminSurface(ControlPlane controlPlane, String password) {
        this.controlPlane = Preconditions.requireNonNull(controlPlane, "controlPlane");
        this.password = Preconditions.requireNonBlank(password, "password").getBytes(StandardCharsets.UTF_8);
    }

    public boolean handles(String path) {
        return path.equals("/admin") || path.equals("/admin/") || path.startsWith("/admin/");
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/admin") || path.equals("/admin/")) {
            servePage(exchange);
            return;
        }
        if (!path.startsWith("/admin/api/")) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        String action = path.substring("/admin/api/".length());
        try {
            if (action.equals("login")) {
                handleLogin(exchange);
                return;
            }
            if (!authorized(exchange)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            switch (action) {
                case "servers" -> sendJson(exchange, 200, serversJson());
                case "servers/create" -> handleCreate(exchange);
                case "servers/remove" -> handleRemove(exchange);
                case "pack" -> handlePack(exchange);
                case "regenerate" -> mutate(exchange, ManagedPack::regenerate);
                case "version" -> handleSetVersion(exchange);
                case "whitelist-gate" -> handleSetWhitelistGate(exchange);
                case "upload" -> handleUpload(exchange);
                case "file/delete" -> handleDeleteFile(exchange);
                default -> sendJson(exchange, 404, Map.of("error", "unknown action"));
            }
        } catch (NotFound e) {
            sendJson(exchange, 404, Map.of("error", e.getMessage()));
        } catch (SecurityException | IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // --- auth ----------------------------------------------------------------

    private void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        String provided = String.valueOf(body.getOrDefault("password", ""));
        if (MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), password)) {
            String token = UUID.randomUUID().toString();
            sessions.add(token);
            sendJson(exchange, 200, Map.of("token", token));
        } else {
            sendJson(exchange, 401, Map.of("error", "wrong password"));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Admin-Token");
        return token != null && sessions.contains(token);
    }

    // --- server-level actions ------------------------------------------------

    private void handleCreate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        String name = str(body, "name");
        String dir = str(body, "dir");
        String portText = str(body, "port");
        if (name.isEmpty() || dir.isEmpty() || portText.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "name, dir and port are required"));
            return;
        }
        int port = Integer.parseInt(portText);
        Optional<String> clientToken = optional(str(body, "clientToken"));
        controlPlane.createServer(name, Path.of(dir), port, clientToken);
        sendJson(exchange, 200, serversJson());
    }

    private void handleRemove(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        boolean removed = controlPlane.removeServer(str(body, "name"));
        sendJson(exchange, removed ? 200 : 404,
                removed ? serversJson() : Map.of("error", "no such server"));
    }

    // --- pack-level actions --------------------------------------------------

    private void handlePack(HttpExchange exchange) throws IOException {
        ServerNode node = resolveNode(exchange, Map.of());
        sendJson(exchange, 200, packJson(node));
    }

    private void handleSetVersion(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        ServerNode node = resolveNode(exchange, body);
        String version = str(body, "version");
        if (version.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "version required"));
            return;
        }
        node.pack().setVersion(PackVersion.of(version));
        sendJson(exchange, 200, packJson(node));
    }

    private void handleSetWhitelistGate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        ServerNode node = resolveNode(exchange, body);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        node.setWhitelistGateEnabled(enabled);
        sendJson(exchange, 200, packJson(node));
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        ServerNode node = resolveNode(exchange, Map.of());
        String relPath = queryParam(exchange, "path");
        if (relPath == null || relPath.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "path query param required"));
            return;
        }
        // Bulk uploads pass regen=false and call /regenerate once at the end.
        boolean regenerate = !"false".equalsIgnoreCase(queryParam(exchange, "regen"));
        byte[] content = exchange.getRequestBody().readAllBytes();
        node.pack().addFile(new RelativePath(relPath), content, regenerate);
        sendJson(exchange, 200, regenerate ? packJson(node) : Map.of("ok", true));
    }

    private void handleDeleteFile(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        ServerNode node = resolveNode(exchange, body);
        String relPath = str(body, "path");
        if (relPath.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "path required"));
            return;
        }
        boolean removed = node.pack().deleteFile(new RelativePath(relPath));
        sendJson(exchange, removed ? 200 : 404,
                removed ? packJson(node) : Map.of("error", "no such file"));
    }

    private void mutate(HttpExchange exchange, java.util.function.Consumer<ManagedPack> op) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        ServerNode node = resolveNode(exchange, body);
        op.accept(node.pack());
        sendJson(exchange, 200, packJson(node));
    }

    // --- views ---------------------------------------------------------------

    private List<Object> serversJson() {
        List<Object> servers = new ArrayList<>();
        for (ServerNode node : controlPlane.list()) {
            GeneratedPack pack = node.pack().current();
            long files = 0;
            long bytes = 0;
            for (CategoryIndex index : pack.indices()) {
                for (FileEntry entry : index.entries()) {
                    files++;
                    bytes += entry.sizeBytes();
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", node.name());
            s.put("port", node.port());
            s.put("packId", pack.root().packId());
            s.put("version", pack.root().packVersion().raw());
            s.put("totalFiles", files);
            s.put("totalBytes", bytes);
            s.put("clientTokenRequired", node.clientTokenRequired());
            s.put("whitelistGateEnabled", node.whitelistGateEnabled());
            s.put("packDir", node.pack().packDir().toString());
            servers.add(s);
        }
        return servers;
    }

    private Map<String, Object> packJson(ServerNode node) {
        GeneratedPack current = node.pack().current();
        List<Object> categories = new ArrayList<>();
        long totalFiles = 0;
        long totalBytes = 0;
        for (CategoryIndex index : current.indices()) {
            List<Object> files = new ArrayList<>();
            for (FileEntry entry : index.entries()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", entry.path().value());
                f.put("size", entry.sizeBytes());
                f.put("hash", entry.hash().value());
                files.add(f);
                totalFiles++;
                totalBytes += entry.sizeBytes();
            }
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("category", index.category().name());
            cat.put("files", files);
            categories.add(cat);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", node.name());
        out.put("port", node.port());
        out.put("packId", current.root().packId());
        out.put("version", current.root().packVersion().raw());
        out.put("generatedAt", current.root().generatedAt().toString());
        out.put("clientTokenRequired", node.clientTokenRequired());
        out.put("whitelistGateEnabled", node.whitelistGateEnabled());
        out.put("packDir", node.pack().packDir().toString());
        out.put("totalFiles", totalFiles);
        out.put("totalBytes", totalBytes);
        out.put("categories", categories);
        return out;
    }

    // --- helpers -------------------------------------------------------------

    private ServerNode resolveNode(HttpExchange exchange, Map<String, Object> body) {
        String fromQuery = queryParam(exchange, "server");
        String name = (fromQuery == null || fromQuery.isBlank())
                ? String.valueOf(body.getOrDefault("server", "")) : fromQuery;
        return controlPlane.get(name).orElseThrow(() -> new NotFound("no such server: " + name));
    }

    private void servePage(HttpExchange exchange) throws IOException {
        byte[] page;
        try (InputStream in = AdminSurface.class.getResourceAsStream("/admin/index.html")) {
            if (in == null) {
                sendText(exchange, 500, "admin page missing");
                return;
            }
            page = in.readAllBytes();
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Map.of();
        }
        Object parsed = Json.read(new String(body, StandardCharsets.UTF_8));
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    private static String str(Map<String, Object> body, String key) {
        return String.valueOf(body.getOrDefault(key, "")).trim();
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }
}
