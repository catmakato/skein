package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.ClientPlatform;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the disconnect-driven sync, with no Minecraft dependency so it is
 * fully unit-testable: derive the conventional Skein URL from the address the
 * player tried, probe it, and — if it answers — sync from it. The loader hook is
 * a thin caller of this class.
 */
public final class SkeinJoinSync {

    private final ClientPlatform platform;
    private final EndpointProbe probe;

    public SkeinJoinSync(ClientPlatform platform, EndpointProbe probe) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.probe = Preconditions.requireNonNull(probe, "probe");
    }

    /** Find Skein on the same host: try the conventional port, then scan TCP ports. */
    public Optional<String> discover(String mcAddress, Optional<String> token) {
        Optional<String> conventional = discoverAt(SkeinEndpoint.deriveFrom(mcAddress), token);
        if (conventional.isPresent()) return conventional;

        String host = SkeinEndpoint.host(mcAddress);
        int minecraftPort = SkeinEndpoint.minecraftPort(mcAddress);
        AtomicInteger nextPort = new AtomicInteger(1);
        AtomicReference<String> found = new AtomicReference<>();
        var executor = Executors.newFixedThreadPool(128, task -> {
            Thread thread = new Thread(task, "skein-port-discovery");
            thread.setDaemon(true);
            return thread;
        });
        var completed = new ExecutorCompletionService<Void>(executor);
        List<java.util.concurrent.Future<Void>> workers = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            workers.add(completed.submit(() -> {
                while (found.get() == null) {
                    int scanIndex = nextPort.getAndIncrement();
                    if (scanIndex > 65535) break;
                    int port = scanIndex == 1
                            ? minecraftPort
                            : ((minecraftPort + scanIndex - 2) % 65535) + 1;
                    if (port == SkeinEndpoint.DEFAULT_SKEIN_PORT) continue;
                    if (!acceptsTcp(host, port)) continue;
                    String url = SkeinEndpoint.deriveFrom(mcAddress, port);
                    if (probe.probe(url, token)) found.compareAndSet(null, url);
                }
                return null;
            }));
        }
        try {
            for (int i = 0; i < workers.size(); i++) {
                completed.take().get();
                if (found.get() != null) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // A worker failure should not prevent remaining workers from finishing.
        } finally {
            workers.forEach(worker -> worker.cancel(true));
            executor.shutdownNow();
        }
        return Optional.ofNullable(found.get());
    }

    private static boolean acceptsTcp(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 75);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Probe an already-formed base URL (used by tests and manual entry validation). */
    public Optional<String> discoverAt(String baseUrl, Optional<String> token) {
        return probe.probe(baseUrl, token) ? Optional.of(baseUrl) : Optional.empty();
    }

    /** Run a sync session against an explicit base URL. Never throws. */
    public SyncOutcome syncFrom(String baseUrl, Optional<String> token,
                                SyncProgressListener listener) {
        return new SkeinClientSession(platform, listener)
                .run(baseUrl, token, Optional.empty());
    }
}
