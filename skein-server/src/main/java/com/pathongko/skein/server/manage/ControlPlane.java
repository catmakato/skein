package com.pathongko.skein.server.manage;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.http.SkeinHttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link ServerNode}s — the heart of the multi-server control panel.
 * Holds every managed pack/server and can spin up new ones (each on its own port)
 * or shut them down at runtime. Thread-safe.
 */
public final class ControlPlane {

    private final Map<String, ServerNode> nodes = new ConcurrentHashMap<>();
    private final HashService hashService;
    private final JsonManifestCodec codec;
    private final HashAlgorithm algorithm;
    private final Map<FileCategory, String> categoryDirs;

    public ControlPlane(HashService hashService, JsonManifestCodec codec, HashAlgorithm algorithm,
                        Map<FileCategory, String> categoryDirs) {
        this.hashService = Preconditions.requireNonNull(hashService, "hashService");
        this.codec = Preconditions.requireNonNull(codec, "codec");
        this.algorithm = Preconditions.requireNonNull(algorithm, "algorithm");
        this.categoryDirs = Map.copyOf(categoryDirs);
    }

    /** Register an already-running node (e.g. the primary server in single-server mode). */
    public void register(ServerNode node) {
        if (nodes.putIfAbsent(node.name(), node) != null) {
            throw new IllegalArgumentException("a server named '" + node.name() + "' already exists");
        }
    }

    /** Create, start and register a new server for {@code packDir} on {@code port}. */
    public ServerNode createServer(String name, Path packDir, int port, Optional<String> clientToken) {
        Preconditions.requireNonBlank(name, "name");
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("a server named '" + name + "' already exists");
        }
        try {
            Files.createDirectories(packDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create pack directory " + packDir, e);
        }
        String baseUrl = "http://127.0.0.1:" + port + "/files/";
        ManagedPack pack = new ManagedPack(packDir, name, PackVersion.of("1.0.0"), algorithm,
                baseUrl, categoryDirs, hashService, codec);
        SkeinHttpServer server = new SkeinHttpServer(
                pack::current, clientToken, Optional.empty(), new InetSocketAddress(port));
        int bound;
        try {
            bound = server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start server on port " + port, e);
        }
        ServerNode node = new ServerNode(name, pack, bound, clientToken, server);
        nodes.put(name, node);
        return node;
    }

    /** Stop and remove a server. */
    public boolean removeServer(String name) {
        ServerNode node = nodes.remove(name);
        if (node == null) {
            return false;
        }
        node.stop();
        return true;
    }

    public Optional<ServerNode> get(String name) {
        return Optional.ofNullable(nodes.get(name));
    }

    /** All nodes, ordered by name for a stable UI. */
    public List<ServerNode> list() {
        return nodes.values().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    public void stopAll() {
        Collection<ServerNode> all = nodes.values();
        all.forEach(ServerNode::stop);
        nodes.clear();
    }
}
