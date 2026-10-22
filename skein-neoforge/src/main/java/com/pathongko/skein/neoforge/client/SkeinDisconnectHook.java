// UNVERIFIED: needs NeoGradle toolchain — compile on build machine.
// Mirrors skein-forge's SkeinDisconnectHook exactly; only the loader-API package
// names differ (net.neoforged.* vs net.minecraftforge.*). RUNTIME-UNVERIFIED: the
// button-render on the disconnect screen and getCurrentServer() timing need a live
// :skein-neoforge:runClient check.
//
// Client-only (references net.minecraft.client.*). Reached only via SkeinClient's
// Dist.CLIENT-guarded init, never from server/common code.
package com.pathongko.skein.neoforge.client;

import com.pathongko.skein.bootstrap.EndpointProbe;
import com.pathongko.skein.bootstrap.RememberedEndpointCache;
import com.pathongko.skein.bootstrap.SkeinEndpoint;
import com.pathongko.skein.bootstrap.SkeinJoinSync;
import com.pathongko.skein.platform.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Adds a "Sync with Skein" button to Minecraft's disconnect screen. This is how a
 * client recovers from a mod-mismatch join rejection: the loader terminates the
 * connection during the login handshake (before the play phase), so Skein cannot
 * sync over the game connection — it must sync over HTTP instead. The button
 * derives the server's Skein URL from the address just tried, probes it, and syncs.
 *
 * <p>All decisions live in the shared, unit-tested {@link SkeinJoinSync}; this class
 * only bridges NeoForge client events to it. Instantiated once, only on the physical
 * client, by {@link SkeinClient}.
 */
public final class SkeinDisconnectHook {

    private final ClientPlatform platform;
    private final Executor syncExecutor;

    public SkeinDisconnectHook(ClientPlatform platform, Executor syncExecutor) {
        this.platform = platform;
        this.syncExecutor = syncExecutor;
    }

    public void install() {
        NeoForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DisconnectedScreen)) {
            return;
        }
        String attempted = currentServerAddress();
        if (attempted == null) {
            return; // nothing to sync against
        }
        int centerX = event.getScreen().width / 2;
        int y = 28;
        Button sync = Button.builder(
                        Component.literal("Sync with Skein"),
                        button -> startSync(attempted))
                .bounds(centerX - 100, y, 200, 20)
                .build();
        event.addListener(sync);
    }

    /** Address the client last tried to connect to, e.g. {@code "1.2.3.4:25565"}. */
    private static String currentServerAddress() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server == null ? null : server.ip;
    }

    private void startSync(String mcAddress) {
        SkeinProgressScreen screen = new SkeinProgressScreen();
        Minecraft.getInstance().setScreen(screen);
        SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(10)));
        RememberedEndpointCache remembered = new RememberedEndpointCache(platform.paths().skeinDir());
        syncExecutor.execute(() -> {
            Optional<String> url = join.discover(mcAddress, Optional.empty());
            // Auto-discovery (the conventional port) found nothing, but the player has
            // typed a working URL for this exact server before: try that before asking
            // again.
            if (url.isEmpty()) {
                url = remembered.get(mcAddress)
                        .flatMap(rememberedUrl -> join.discoverAt(rememberedUrl, Optional.empty()));
            }
            if (url.isPresent()) {
                join.syncFrom(url.get(), Optional.empty(), screen);
            } else {
                screen.requestManualUrl(
                        SkeinEndpoint.deriveFrom(mcAddress),
                        typed -> syncExecutor.execute(() -> {
                            // Only remember a URL we've actually confirmed answers.
                            if (join.discoverAt(typed, Optional.empty()).isPresent()) {
                                remembered.put(mcAddress, typed);
                            }
                            join.syncFrom(typed, Optional.empty(), screen);
                        }));
            }
        });
    }
}
