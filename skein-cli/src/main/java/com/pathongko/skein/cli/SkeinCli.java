package com.pathongko.skein.cli;

import com.pathongko.skein.bootstrap.SyncComponents;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import com.pathongko.skein.server.admin.AdminHttpServer;
import com.pathongko.skein.server.admin.AdminSurface;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manage.ControlPlane;
import com.pathongko.skein.server.manage.ManagedPack;
import com.pathongko.skein.server.manage.ServerNode;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line demo that exercises the whole sync pipeline without Minecraft.
 *
 * <pre>
 *   server &lt;packDir&gt; &lt;port&gt; [token]     scan packDir and serve it over HTTP
 *   sync   &lt;http://host:port&gt; &lt;dir&gt; [token]  sync dir from that server
 * </pre>
 */
public final class SkeinCli {

    private static final Map<FileCategory, String> CATEGORY_DIRS = categoryDirs();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "server" -> server(args);
            case "panel" -> panel(args);
            case "sync" -> sync(args);
            default -> usage();
        }
    }

    private static ControlPlane newControlPlane() {
        return new ControlPlane(new MessageDigestHashService(), new JsonManifestCodec(),
                HashAlgorithm.SHA256, CATEGORY_DIRS);
    }

    /** Multi-server control panel: admin UI on its own port; add servers from the UI. */
    private static void panel(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        Optional<String> adminPassword = Optional.empty();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--admin-pass=")) {
                adminPassword = Optional.of(args[i].substring("--admin-pass=".length()));
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.isEmpty() || adminPassword.isEmpty()) {
            System.out.println("usage: panel <adminPort> --admin-pass=<password>");
            return;
        }
        int adminPort = Integer.parseInt(positional.get(0));
        ControlPlane plane = newControlPlane();
        AdminSurface admin = new AdminSurface(plane, adminPassword.get());
        AdminHttpServer server = new AdminHttpServer(admin, new InetSocketAddress(adminPort));
        int bound = server.start();
        System.out.println("Control panel: http://127.0.0.1:" + bound + "/admin");
        System.out.println("Add servers from the dashboard. Press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    private static void server(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        Optional<String> adminPassword = Optional.empty();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--admin-pass=")) {
                adminPassword = Optional.of(arg.substring("--admin-pass=".length()));
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 2) {
            usage();
            return;
        }
        Path packDir = Path.of(positional.get(0));
        int port = Integer.parseInt(positional.get(1));
        Optional<String> clientToken = positional.size() > 2 && !positional.get(2).isBlank()
                ? Optional.of(positional.get(2)) : Optional.empty();

        HashService hash = new MessageDigestHashService();
        JsonManifestCodec codec = new JsonManifestCodec();
        String baseUrl = "http://127.0.0.1:" + port + "/files/";

        System.out.println("Scanning " + packDir.toAbsolutePath() + " ...");
        ManagedPack managed = new ManagedPack(packDir, "demo-pack", PackVersion.of("1.0.0"),
                HashAlgorithm.SHA256, baseUrl, CATEGORY_DIRS, hash, codec);
        System.out.println("  pack:  " + managed.current().root().packId() + " " + managed.version());
        System.out.println("  files: " + managed.current().contentByHash().size());

        // Register into a control plane so the admin UI can also add more servers.
        ControlPlane plane = newControlPlane();
        Optional<AdminSurface> admin = adminPassword.map(pw -> new AdminSurface(plane, pw));
        SkeinHttpServer server = new SkeinHttpServer(
                managed::current, clientToken, admin, new InetSocketAddress(port));
        int bound = server.start();
        plane.register(new ServerNode("demo-pack", managed, bound, clientToken, server));

        System.out.println("Serving on http://127.0.0.1:" + bound
                + (clientToken.isPresent() ? "  (client token required)" : ""));
        admin.ifPresent(a -> System.out.println("Admin UI: http://127.0.0.1:" + bound + "/admin"));
        System.out.println("Try:  sync http://127.0.0.1:" + bound + " <targetDir>"
                + clientToken.map(t -> " " + t).orElse(""));
        System.out.println("Press Ctrl+C to stop.");
        new CountDownLatch(1).await(); // block forever
    }

    private static void sync(String[] args) {
        if (args.length < 3) {
            usage();
            return;
        }
        String endpoint = args[1];
        Path targetDir = Path.of(args[2]);
        Optional<String> token = args.length > 3 ? Optional.of(args[3]) : Optional.empty();

        EventBus events = new SynchronousEventBus();
        events.subscribe(SkeinCli::printEvent);
        // Known limitation, not a bug: the CLI has no persisted ledger of its own
        // (unlike SkeinClientSession's InstalledPathsCache), so every run passes an
        // empty set here. It stays permanently on additive-only-equivalent behavior
        // — a mod dropped or renamed by a pack update is never cleaned up via the
        // CLI, no matter how many times it runs. See "Known limitation" in
        // docs/superpowers/specs/2026-08-09-install-ledger-design.md. A CLI-side
        // ledger is a reasonable follow-up but is separate work.
        SyncOrchestrator orchestrator =
                SyncComponents.forSync(endpoint, targetDir, token, events, Set.of());

        System.out.println("Syncing " + targetDir.toAbsolutePath() + " from " + endpoint + " ...");
        SyncOutcome outcome = orchestrator.run();
        System.out.println("Outcome: " + describe(outcome));
        System.exit(outcome.isSuccess() ? 0 : 1);
    }

    private static void printEvent(SyncEvent event) {
        if (event instanceof SyncEvent.StateChanged s) {
            System.out.println("  [state] " + s.to());
        } else if (event instanceof SyncEvent.DiffComputed d) {
            System.out.println("  [diff]  " + d.fetches() + " to fetch, " + d.deletions()
                    + " to remove, " + d.bytesToDownload() + " bytes");
        } else if (event instanceof SyncEvent.ProgressUpdated p && p.bytesTotal() > 0) {
            long pct = p.bytesDone() * 100 / p.bytesTotal();
            System.out.print("\r  [dl]    " + pct + "%   ");
            if (p.bytesDone() >= p.bytesTotal()) {
                System.out.println();
            }
        }
    }

    private static String describe(SyncOutcome outcome) {
        if (outcome instanceof SyncOutcome.Failed f) {
            return "FAILED " + f.code() + " (" + f.recoverability() + "): " + f.detail();
        }
        return outcome.getClass().getSimpleName();
    }

    private static Map<FileCategory, String> categoryDirs() {
        Map<FileCategory, String> map = new EnumMap<>(FileCategory.class);
        map.put(FileCategory.MOD, "mods");
        map.put(FileCategory.CONFIG, "config");
        map.put(FileCategory.RESOURCEPACK, "resourcepacks");
        map.put(FileCategory.SHADERPACK, "shaderpacks");
        map.put(FileCategory.DATAPACK, "datapacks");
        return map;
    }

    private static void usage() {
        System.out.println("""
                Skein demo CLI
                  server <packDir> <port> [token]        scan packDir and serve it
                  sync   <http://host:port> <dir> [token] sync dir from a server
                """);
    }

    private SkeinCli() {
    }
}
