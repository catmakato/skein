// Client-only entry point. References net.minecraft.client.* (Minecraft,
// SkeinProgressScreen extends Screen), so it is ONLY class-loaded on Dist.CLIENT —
// SkeinMod reaches it exclusively through its FMLEnvironment.dist == Dist.CLIENT
// guard. Never reference this class from server/common code.
package com.pathongko.skein.forge.client;

import com.pathongko.skein.bootstrap.ClientBootstrap;
import com.pathongko.skein.forge.net.ForgeControlChannel;
import com.pathongko.skein.forge.platform.ForgeClientPlatform;
import com.pathongko.skein.forge.platform.ForgeGameThread;
import com.pathongko.skein.forge.platform.ForgeLoaderInfo;
import com.pathongko.skein.forge.platform.ForgePlatformPaths;
import com.pathongko.skein.forge.platform.ForgeRelauncher;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.InstalledMod;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wires the client side of Skein: builds the {@link ClientPlatform} from the Forge
 * platform impls, installs the {@link ClientBootstrap}, and on join opens the
 * {@link SkeinProgressScreen} and sends the handshake reply.
 *
 * <p>Instantiated once, only on the physical client, by
 * {@link com.pathongko.skein.forge.SkeinMod}. All sync decisions live in the shared
 * {@code skein-bootstrap}/{@code skein-core} modules; this class only bridges Forge
 * client events to them.
 */
public final class SkeinClient {

    private static final String MOD_ID = "skein";

    private final ForgeControlChannel controlChannel;

    // Built once in onClientSetup(); read by the join listener. Set exactly once from
    // the client-setup work queue, so a plain volatile is enough for the
    // happens-before edge into the later event-bus callback.
    private volatile ClientBootstrap clientBootstrap;
    private volatile SkeinProgressScreen progressScreen;

    private SkeinClient(ForgeControlChannel controlChannel) {
        this.controlChannel = controlChannel;
    }

    /** Called once from {@code SkeinMod}, only on {@code Dist.CLIENT}. */
    public static void init(ForgeControlChannel controlChannel, IEventBus modEventBus) {
        SkeinClient client = new SkeinClient(controlChannel);
        modEventBus.addListener(client::onClientSetup);
        // Game/session events (as opposed to mod-lifecycle events) are posted to
        // MinecraftForge.EVENT_BUS, not the mod bus.
        MinecraftForge.EVENT_BUS.addListener(client::onClientLoggingIn);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // FMLClientSetupEvent extends ParallelDispatchEvent; enqueueWork() defers this
        // until every mod's parallel setup has finished, which matters because the
        // ModList lookup below assumes all mods are registered.
        event.enqueueWork(() -> {
            ForgePlatformPaths paths = new ForgePlatformPaths(FMLPaths.GAMEDIR.get());
            ForgeGameThread gameThread = new ForgeGameThread();
            ForgeRelauncher relauncher = new ForgeRelauncher();

            // mods.toml already pins this mod to exactly 1.20.1, so the MC version is
            // known; ModList gives this mod's own version.
            String mcVersion = "1.20.1";
            String skeinVersion = ModList.get().getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            // confirm on build machine: IModInfo#getModId()/getVersion() for 1.20.1/Forge 47.x.
            List<InstalledMod> installedMods = ModList.get().getMods().stream()
                    .map(info -> new InstalledMod(info.getModId(), info.getVersion().toString()))
                    .toList();
            ForgeLoaderInfo loaderInfo =
                    new ForgeLoaderInfo(mcVersion, skeinVersion, installedMods);

            ClientPlatform platform = new ForgeClientPlatform(
                    loaderInfo, paths, gameThread, controlChannel, relauncher);

            SkeinProgressScreen screen = new SkeinProgressScreen(relauncher, paths.gameDir());
            Executor syncExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skein-sync");
                t.setDaemon(true);
                return t;
            });
            ClientBootstrap boot = new ClientBootstrap(platform, syncExecutor, screen);
            boot.install();

            // Recover from mod-mismatch join rejections: Forge kills the connection in
            // the login phase (before onClientLoggingIn ever fires), so add a
            // "Sync with Skein" button to the disconnect screen that syncs over HTTP.
            com.mojang.logging.LogUtils.getLogger().info("[Skein] client setup running; installing disconnect hook");
            new SkeinDisconnectHook(platform, syncExecutor).install();

            this.progressScreen = screen;
            this.clientBootstrap = boot;
        });
    }

    /**
     * Fired when the client finishes logging in to a server. Opens the progress
     * screen and sends the handshake reply so the server side can compute and grant
     * a session.
     */
    private void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientBootstrap boot = this.clientBootstrap;
        SkeinProgressScreen screen = this.progressScreen;
        if (boot == null || screen == null) {
            // Client setup hasn't completed yet — shouldn't happen in practice since
            // FMLClientSetupEvent fires long before any server connection is possible.
            return;
        }
        // This event follows network handshake processing and fires on the client
        // thread, so setScreen(...) is safe here.
        Minecraft.getInstance().setScreen(screen);
        boot.sendHandshakeReply();
    }
}
