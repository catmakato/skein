// Compiles against Forge 47.3.0 / MC 1.20.1 and loads on a dedicated server
// (verified: server boots past mod construction). Client-only code is isolated in
// SkeinClient behind a Dist.CLIENT guard so this class never references
// net.minecraft.client.* — required or Forge's RuntimeDistCleaner rejects the mod
// on DEDICATED_SERVER.
package com.pathongko.skein.forge;

import com.pathongko.skein.bootstrap.MinecraftWhitelist;
import com.pathongko.skein.bootstrap.ServerBootstrap;
import com.pathongko.skein.bootstrap.SkeinServerSession;
import com.pathongko.skein.bootstrap.SyncComponents;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.forge.client.SkeinClient;
import com.pathongko.skein.forge.net.ForgeControlChannel;
import com.pathongko.skein.forge.server.SkeinServerConfig;
import com.pathongko.skein.server.admin.AdminSurface;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manage.ControlPlane;
import com.pathongko.skein.server.manage.ManagedPack;
import com.pathongko.skein.server.manage.ServerNode;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code @Mod("skein")} entry point and Composition Root for the Forge adapter.
 *
 * <p>This class owns no sync logic of its own: its only job is to construct the
 * shared objects from {@code skein-bootstrap}/{@code skein-core}/{@code
 * skein-server} and wire them to Forge's lifecycle/network events. Every decision
 * about what to sync, when, and how to recover from errors lives in the shared
 * modules (already implemented and unit-tested without Minecraft).
 *
 * <h2>Dist split (why the client code isn't here)</h2>
 * A single jar runs on both sides, but Forge's {@code RuntimeDistCleaner} forbids
 * loading client-only classes (e.g. {@code net.minecraft.client.gui.screens.Screen})
 * on a {@code DEDICATED_SERVER}. So this class must not statically reference any
 * {@code net.minecraft.client.*} type. All client wiring (progress screen,
 * {@code Minecraft}, the join listener) lives in {@link SkeinClient}, reached only
 * through the {@code FMLEnvironment.dist == Dist.CLIENT} guard in the constructor —
 * on a server {@code SkeinClient} is never class-loaded.
 */
@Mod("skein")
public final class SkeinMod {

    /** Must exactly match mods.toml's [[mods]] modId. */
    public static final String MOD_ID = "skein";

    private static final String SERVER_PACK_ID = "skein-pack";
    private static final PackVersion INITIAL_PACK_VERSION = PackVersion.of("1.0.0");

    private final ForgeControlChannel controlChannel = new ForgeControlChannel();

    public SkeinMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the single control channel immediately; both client and server
        // setup events (and the handshake they trigger) depend on it existing.
        controlChannel.register();

        modEventBus.addListener(this::onDedicatedServerSetup);

        // Client-only wiring. The guard keeps SkeinClient (and every client class it
        // touches) off the dedicated server's class path — Java resolves the
        // invokestatic lazily, so on a server this branch never loads SkeinClient.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            SkeinClient.init(controlChannel, modEventBus);
        }
    }

    // =====================================================================
    // Dedicated server side
    // =====================================================================

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        // FMLDedicatedServerSetupEvent extends ParallelDispatchEvent; enqueueWork()
        // defers this until all mods have finished the parallel setup phase.
        event.enqueueWork(() -> {
            try {
                startServer();
            } catch (IOException e) {
                // confirm on build machine: replace with skein-common's logging facade
                // once that dependency is wired into this module; a bare stack trace is
                // a placeholder so a misconfigured server doesn't fail silently.
                throw new RuntimeException("Skein: failed to start server-side sync services", e);
            }
        });
    }

    private void startServer() throws IOException {
        Path configDir = FMLPaths.CONFIGDIR.get();
        SkeinServerConfig cfg = SkeinServerConfig.load(configDir);

        // Mirrors SkeinCli.server(): the admin surface (when a password is configured)
        // sits over a ControlPlane that this one pack is registered into, so the admin
        // dashboard's server list and per-server API calls (which resolve by name) are
        // actually functional rather than pointing at an empty plane.
        ControlPlane controlPlane = new ControlPlane(new MessageDigestHashService(), new JsonManifestCodec(),
                HashAlgorithm.SHA256, SyncComponents.CATEGORY_DIRS);
        Optional<AdminSurface> admin = cfg.adminPassword().map(password -> new AdminSurface(controlPlane, password));

        AtomicReference<ManagedPack> managedRef = new AtomicReference<>();
        SkeinHttpServer httpServer = new SkeinHttpServer(
                () -> {
                    ManagedPack pack = managedRef.get();
                    if (pack == null) throw new IllegalStateException("Skein pack is not ready");
                    return pack.current();
                }, cfg.clientToken(), admin, new InetSocketAddress(cfg.httpPort()));
        int boundPort = httpServer.start();
        String endpoint = endpointWithPort(cfg.publicEndpoint(), boundPort);
        if (boundPort != cfg.httpPort()) {
            System.err.println("[Skein] HTTP port " + cfg.httpPort()
                    + " was occupied; using available port " + boundPort);
        }
        ManagedPack managed = new ManagedPack(
                cfg.packDir(), SERVER_PACK_ID, INITIAL_PACK_VERSION,
                HashAlgorithm.SHA256, endpoint + "/files/", SyncComponents.CATEGORY_DIRS,
                new MessageDigestHashService(), new JsonManifestCodec());
        managedRef.set(managed);
        ServerNode node = new ServerNode(SERVER_PACK_ID, managed, boundPort, cfg.clientToken(), httpServer);
        controlPlane.register(node);

        // whitelist.json lives one level above the config dir — the same "server
        // root" convention SkeinServerConfig's default packDir already uses —
        // independent of wherever an operator may have redirected packDir itself.
        Path whitelistFile = configDir.resolve("..").normalize().resolve("whitelist.json");
        MinecraftWhitelist whitelist = new MinecraftWhitelist(whitelistFile);

        new ServerBootstrap(controlChannel,
                new SkeinServerSession(endpoint, cfg.clientToken(), managed::current,
                        new MessageDigestHashService(), node::whitelistGateEnabled, whitelist))
                .install();
    }

    private static String endpointWithPort(String endpoint, int port) {
        URI uri = URI.create(endpoint);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("publicEndpoint must be an absolute HTTP URL: " + endpoint);
        }
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), port,
                    uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("invalid publicEndpoint: " + endpoint, e);
        }
    }
}
