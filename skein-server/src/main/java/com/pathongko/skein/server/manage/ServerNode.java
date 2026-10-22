package com.pathongko.skein.server.manage;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.server.http.SkeinHttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One managed server in the control plane: a named {@link ManagedPack} plus the
 * running {@link SkeinHttpServer} that serves it on its own port. The control
 * plane can hold several of these, so one admin dashboard manages many packs.
 */
public final class ServerNode {

    private static final String WHITELIST_GATE_MARKER = ".whitelist-gate-enabled";

    private final String name;
    private final ManagedPack pack;
    private final int port;
    private final Optional<String> clientToken;
    private final SkeinHttpServer server;
    private final AtomicBoolean whitelistGateEnabled;

    public ServerNode(String name, ManagedPack pack, int port,
                      Optional<String> clientToken, SkeinHttpServer server) {
        this.name = Preconditions.requireNonBlank(name, "name");
        this.pack = Preconditions.requireNonNull(pack, "pack");
        this.port = port;
        this.clientToken = Preconditions.requireNonNull(clientToken, "clientToken");
        this.server = Preconditions.requireNonNull(server, "server");
        this.whitelistGateEnabled = new AtomicBoolean(Files.exists(whitelistGateMarker()));
    }

    public String name() {
        return name;
    }

    public ManagedPack pack() {
        return pack;
    }

    public int port() {
        return port;
    }

    public boolean clientTokenRequired() {
        return clientToken.isPresent();
    }

    /**
     * Whether sync is restricted to players on this server's Minecraft whitelist.
     * Defaults to {@code false} and, once set, survives a restart via a marker
     * file under {@link ManagedPack#packDir()} — see
     * docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md for why a
     * security-relevant toggle must not silently reset to "open" on every boot.
     */
    public boolean whitelistGateEnabled() {
        return whitelistGateEnabled.get();
    }

    public synchronized void setWhitelistGateEnabled(boolean enabled) {
        Path marker = whitelistGateMarker();
        try {
            if (enabled) {
                Files.createDirectories(pack.packDir());
                if (!Files.exists(marker)) {
                    Files.createFile(marker);
                }
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist whitelist gate setting under " + pack.packDir(), e);
        }
        // Only update in-memory state after disk operation succeeds
        whitelistGateEnabled.set(enabled);
    }

    private Path whitelistGateMarker() {
        return pack.packDir().resolve(WHITELIST_GATE_MARKER);
    }

    public void stop() {
        server.stop();
    }
}
