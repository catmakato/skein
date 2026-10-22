// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
// Client-only entry point. References net.minecraft.client.* (Minecraft,
// SkeinProgressScreen extends Screen), so it is ONLY class-loaded on Dist.CLIENT —
// SkeinMod reaches it exclusively through its FMLEnvironment.dist == Dist.CLIENT
// guard. Never reference this class from server/common code.
//
// This mirrors skein-forge's SkeinClient exactly; only the loader-API package names
// differ (net.neoforged.* vs net.minecraftforge.*). The dist split is required or
// NeoForge's RuntimeDistCleaner rejects the mod on a dedicated server (see the same
// crash fixed in skein-forge).
package com.pathongko.skein.neoforge.client;

import com.pathongko.skein.bootstrap.ClientBootstrap;
import com.pathongko.skein.neoforge.net.NeoForgeControlChannel;
import com.pathongko.skein.neoforge.platform.NeoForgeClientPlatform;
import com.pathongko.skein.neoforge.platform.NeoForgeGameThread;
import com.pathongko.skein.neoforge.platform.NeoForgeLoaderInfo;
import com.pathongko.skein.neoforge.platform.NeoForgePlatformPaths;
import com.pathongko.skein.neoforge.platform.NeoForgeRelauncher;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.InstalledMod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wires the client side of Skein: builds the {@link ClientPlatform} from the
 * NeoForge platform impls, installs the {@link ClientBootstrap}, and on join opens
 * the {@link SkeinProgressScreen} and sends the handshake reply.
 *
 * <p>Instantiated once, only on the physical client, by
 * {@link com.pathongko.skein.neoforge.SkeinMod}. All sync decisions live in the
 * shared {@code skein-bootstrap}/{@code skein-core} modules; this class only bridges
 * NeoForge client events to them.
 */
public final class SkeinClient {

    private static final String MOD_ID = "skein";

    private final NeoForgeControlChannel controlChannel;

    private volatile ClientBootstrap clientBootstrap;
    private volatile SkeinProgressScreen progressScreen;

    private SkeinClient(NeoForgeControlChannel controlChannel) {
        this.controlChannel = controlChannel;
    }

    /** Called once from {@code SkeinMod}, only on {@code Dist.CLIENT}. */
    public static void init(NeoForgeControlChannel controlChannel, IEventBus modEventBus) {
        SkeinClient client = new SkeinClient(controlChannel);
        modEventBus.addListener(client::onClientSetup);
        // Game/session events (as opposed to mod-lifecycle events) are posted to
        // NeoForge.EVENT_BUS, not the mod bus.
        NeoForge.EVENT_BUS.addListener(client::onClientLoggingIn);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            NeoForgePlatformPaths paths = new NeoForgePlatformPaths(FMLPaths.GAMEDIR.get());
            NeoForgeGameThread gameThread = new NeoForgeGameThread();
            NeoForgeRelauncher relauncher = new NeoForgeRelauncher();

            String mcVersion = "1.20.1";
            String skeinVersion = ModList.get().getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            NeoForgeLoaderInfo loaderInfo =
                    new NeoForgeLoaderInfo(mcVersion, skeinVersion, List.<InstalledMod>of());

            ClientPlatform platform = new NeoForgeClientPlatform(
                    loaderInfo, paths, gameThread, controlChannel, relauncher);

            SkeinProgressScreen screen = new SkeinProgressScreen();
            Executor syncExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skein-sync");
                t.setDaemon(true);
                return t;
            });
            ClientBootstrap boot = new ClientBootstrap(platform, syncExecutor, screen);
            boot.install();

            // Recover from mod-mismatch join rejections: the loader kills the
            // connection in the login phase (before onClientLoggingIn ever fires), so
            // add a "Sync with Skein" button to the disconnect screen that syncs over
            // HTTP.
            new SkeinDisconnectHook(platform, syncExecutor).install();

            this.progressScreen = screen;
            this.clientBootstrap = boot;
        });
    }

    private void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientBootstrap boot = this.clientBootstrap;
        SkeinProgressScreen screen = this.progressScreen;
        if (boot == null || screen == null) {
            return;
        }
        Minecraft.getInstance().setScreen(screen);
        boot.sendHandshakeReply();
    }
}
