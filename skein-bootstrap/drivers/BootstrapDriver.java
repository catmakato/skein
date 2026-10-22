import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.bootstrap.SkeinClientSession;
import com.pathongko.skein.bootstrap.SyncComponents;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.ClientRelauncher;
import com.pathongko.skein.platform.GameThreadExecutor;
import com.pathongko.skein.platform.InstalledMod;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.LoaderType;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manage.ManagedPack;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Standalone, dependency-free driver that exercises a full client sync session
 * without Gradle or JUnit: it starts a real {@link SkeinHttpServer} over a temp
 * pack dir, points a minimal inline {@link ClientPlatform} at a temp game dir,
 * runs a {@link SkeinClientSession} against a matching {@link SessionGrantS2C},
 * and asserts the mod landed on disk and the sync outcome succeeded.
 *
 * <p>Mirrors {@code skein-bootstrap/src/test/java/.../support/LocalPackServer}
 * and {@code FakeClientPlatform} (Task A4), but inlined here so this file has
 * zero dependency on the test sources and can be compiled/run standalone per
 * the repo's javac quick-check convention (see CLAUDE.md).
 *
 * <p>Not part of the Gradle source set — lives under {@code drivers/}, compiled
 * and run manually against the module {@code build/classes/java/main} dirs.
 */
public final class BootstrapDriver {

    private BootstrapDriver() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("skein-bootstrap-driver");
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");

        Files.createDirectories(packDir.resolve("mods"));
        Files.write(packDir.resolve("mods").resolve("example.jar"), "hello-mod".getBytes());

        // Bind the server FIRST (port 0 -> ephemeral), then build the ManagedPack
        // with the real port: ManifestGenerator bakes the download baseUrl in at
        // generation time, so the port must be known before the manifest exists.
        SkeinHttpServer server = new SkeinHttpServer(BootstrapDriver::currentPack,
                Optional.empty(), Optional.empty(), new InetSocketAddress("127.0.0.1", 0));
        int port = server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + port + "/files/";
            managedPack = new ManagedPack(packDir, "driver-pack", PackVersion.of("1.0.0"),
                    HashAlgorithm.SHA256, baseUrl, SyncComponents.CATEGORY_DIRS,
                    new MessageDigestHashService(), new JsonManifestCodec());

            String rootHash = new MessageDigestHashService()
                    .hash(managedPack.current().encodedRoot(), HashAlgorithm.SHA256).value();
            String endpoint = "http://127.0.0.1:" + port;

            InlineClientPlatform platform = new InlineClientPlatform(gameDir);
            SkeinClientSession session = new SkeinClientSession(platform, event -> System.out.println(event));

            SyncOutcome outcome = session.run(new SessionGrantS2C(endpoint, "", "1.0.0", rootHash));

            Path modFile = gameDir.resolve("mods").resolve("example.jar");
            if (!Files.exists(modFile)) {
                throw new AssertionError("expected mod file at " + modFile + " but it does not exist");
            }
            if (!outcome.isSuccess()) {
                throw new AssertionError("expected a successful sync outcome but got " + outcome);
            }

            System.out.println("mod file present: " + modFile);
            System.out.println("outcome: " + outcome);
            System.out.println("DRIVER OK");
        } finally {
            server.stop();
        }
    }

    // Set once, right after the ManagedPack is built, and read back by the
    // server's content supplier -- mirrors LocalPackServer's currentPack().
    private static volatile ManagedPack managedPack;

    private static com.pathongko.skein.server.manifest.GeneratedPack currentPack() {
        return managedPack.current();
    }

    /** A minimal {@link ClientPlatform}: real temp-dir paths, everything else stubbed/no-op. */
    private static final class InlineClientPlatform implements ClientPlatform {

        private final PlatformPaths paths;

        InlineClientPlatform(Path gameDir) {
            this.paths = new PlatformPaths() {
                @Override
                public Path gameDir() {
                    return gameDir;
                }

                @Override
                public Path configDir() {
                    return gameDir.resolve("config");
                }

                @Override
                public Path modsDir() {
                    return gameDir.resolve("mods");
                }

                @Override
                public Path skeinDir() {
                    return gameDir.resolve(".skein");
                }
            };
        }

        @Override
        public LoaderInfo loaderInfo() {
            return new LoaderInfo() {
                @Override
                public LoaderType loader() {
                    return LoaderType.NEOFORGE;
                }

                @Override
                public String minecraftVersion() {
                    return "1.20.1";
                }

                @Override
                public String skeinVersion() {
                    return "0.1.0";
                }

                @Override
                public List<InstalledMod> installedMods() {
                    return List.of();
                }
            };
        }

        @Override
        public PlatformPaths paths() {
            return paths;
        }

        @Override
        public GameThreadExecutor gameThread() {
            return Runnable::run;
        }

        @Override
        public ControlChannel controlChannel() {
            return new ControlChannel() {
                @Override
                public void sendToServer(byte[] payload) {
                    // no-op: the driver never sends control packets
                }

                @Override
                public void sendToPlayer(PlayerRef player, byte[] payload) {
                    // no-op
                }

                @Override
                public void onReceive(InboundHandler handler) {
                    // no-op
                }
            };
        }

        @Override
        public ClientRelauncher relauncher() {
            return new ClientRelauncher() {
                @Override
                public boolean canRelaunch() {
                    return false;
                }

                @Override
                public void relaunch() {
                    throw new UnsupportedOperationException("relaunch not supported by the driver");
                }
            };
        }
    }
}
