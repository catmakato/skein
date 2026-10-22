// Compiles against Forge 47.x / MC 1.20.1 (verified via :skein-forge:compileJava).
// RUNTIME-UNVERIFIED: the actual button-render on the disconnect screen and the
// getCurrentServer() timing need a live :skein-forge:runClient check — see notes below.
//
// Client-only (references net.minecraft.client.*). Reached only via SkeinClient's
// Dist.CLIENT-guarded init, never from server/common code.
package com.pathongko.skein.forge.client;

import com.pathongko.skein.bootstrap.EndpointProbe;
import com.pathongko.skein.bootstrap.RememberedEndpointCache;
import com.pathongko.skein.bootstrap.SkeinEndpoint;
import com.pathongko.skein.bootstrap.SkeinJoinSync;
import com.pathongko.skein.platform.ClientPlatform;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModMismatchDisconnectedScreen;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a "Sync with Skein" button to Minecraft's disconnect screen. This is how a
 * client recovers from a mod-mismatch join rejection: Forge terminates the
 * connection during the login handshake (before the play phase), so Skein cannot
 * sync over the game connection — it must sync over HTTP instead. The button
 * derives the server's Skein URL from the address just tried, probes it, and syncs.
 *
 * <p>All decisions live in the shared, unit-tested {@link SkeinJoinSync}; this class
 * only bridges Forge client events to it. Instantiated once, only on the physical
 * client, by {@link SkeinClient}.
 *
 * <p>RUNTIME-UNVERIFIED notes (confirm on :skein-forge:runClient):
 * <ul>
 *   <li>{@code ScreenEvent.Init.Post.addListener(Button)} actually renders the button
 *       on the disconnect screen.</li>
 * </ul>
 *
 * <p><b>Why the address comes from a log watcher, not {@code getCurrentServer()}:</b>
 * confirmed against real decompiled 1.20.1 source, {@code Minecraft#getCurrentServer()}
 * is {@code Optionull.map(this.getConnection(), ClientPacketListener::getServerData)} —
 * derived from the post-login play-phase connection. It is therefore {@code null} for
 * the entire login phase on every join, so it can never be populated when a
 * mod-mismatch (or any other login-phase) disconnect happens — confirmed live in-game,
 * including for a server-list join, not just direct-connect. {@code ConnectScreen}
 * itself (the vanilla "Connecting to the server..." screen every join funnels through)
 * never stores the address on any instance field either — only as local variables
 * inside its connecting {@code Thread}'s closure. The one externally observable signal
 * is the log line {@code ConnectScreen} emits right as it starts connecting:
 * {@code LOGGER.info("Connecting to {}, {}", host, port)}. {@link #installConnectAddressWatcher()}
 * attaches a Log4j2 appender directly to that class's logger (found by name, since
 * {@link LogUtils#getLogger()} resolves to the caller's fully-qualified class name) to
 * capture it the moment any connection attempt begins, regardless of how it ends.
 */
public final class SkeinDisconnectHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CONNECT_SCREEN_LOGGER_NAME =
            "net.minecraft.client.gui.screens.ConnectScreen";

    private static final Pattern CONNECTING_TO_LOG =
            Pattern.compile("^Connecting to (.+), (\\d+)$");

    private final ClientPlatform platform;
    private final Executor syncExecutor;

    // Written by the log watcher (installConnectAddressWatcher) the moment any
    // connect attempt starts, and opportunistically by the getCurrentServer() check
    // below on the rarer path where that's actually populated (an already-established
    // session disconnected after login, e.g. kicked mid-game) -- see the class
    // javadoc for why the log watcher is the primary, reliable source.
    private volatile String lastServerAddress;

    public SkeinDisconnectHook(ClientPlatform platform, Executor syncExecutor) {
        this.platform = platform;
        this.syncExecutor = syncExecutor;
    }

    public void install() {
        // Explicit event type on the lambda so Forge's addListener reliably infers it
        // (method references can defeat that inference).
        MinecraftForge.EVENT_BUS.addListener(
                (ScreenEvent.Init.Post event) -> onScreenInit(event));
        installConnectAddressWatcher();
        LOGGER.info("[Skein] disconnect hook installed");
    }

    private void installConnectAddressWatcher() {
        org.apache.logging.log4j.core.Logger connectLogger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(CONNECT_SCREEN_LOGGER_NAME);
        ConnectAddressAppender appender = new ConnectAddressAppender();
        appender.start();
        connectLogger.addAppender(appender);
    }

    /** Watches for {@code ConnectScreen}'s own "Connecting to {}, {}" log line. */
    private final class ConnectAddressAppender extends AbstractAppender {
        private ConnectAddressAppender() {
            super("skein-connect-address-watcher", null, null, true, null);
        }

        @Override
        public void append(LogEvent event) {
            Matcher matcher = CONNECTING_TO_LOG.matcher(event.getMessage().getFormattedMessage());
            if (matcher.matches()) {
                lastServerAddress = matcher.group(1) + ":" + matcher.group(2);
                LOGGER.info("[Skein] observed connect attempt to {}", lastServerAddress);
            }
        }
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        // Remember the server we're connecting to while it's still known.
        ServerData current = Minecraft.getInstance().getCurrentServer();
        if (current != null && current.ip != null && !current.ip.isBlank()) {
            lastServerAddress = current.ip;
            LOGGER.info("[Skein] remembered server address {} (on {})",
                    current.ip, event.getScreen().getClass().getSimpleName());
        }

        // A mod-list mismatch shows Forge's ModMismatchDisconnectedScreen (not the
        // vanilla DisconnectedScreen); handle both so any disconnect can offer a sync.
        var screen = event.getScreen();
        boolean isDisconnect = screen instanceof DisconnectedScreen
                || screen instanceof ModMismatchDisconnectedScreen;
        if (!isDisconnect) {
            return;
        }
        String attempted = lastServerAddress;
        // Login failures can surface as either Forge's mismatch screen or a plain
        // disconnect screen (notably when another loader compatibility layer is
        // involved). Probe every known attempted host automatically; EndpointProbe
        // only accepts a valid Skein manifest, so ordinary disconnects are harmless.
        if (attempted != null) {
            LOGGER.info("[Skein] disconnect detected; starting automatic server discovery");
            startSync(attempted);
            return;
        }
        // Always offer the button. If we captured the address, discovery is automatic;
        // otherwise the button opens the manual-URL field (the approved fallback).
        LOGGER.info("[Skein] disconnect screen {} detected; address={}",
                screen.getClass().getSimpleName(), attempted);
        int centerX = screen.width / 2;
        int y = 28;
        Button sync = Button.builder(
                        Component.literal("Sync with Skein"),
                        button -> startSync(attempted))
                .bounds(centerX - 100, y, 200, 20)
                .build();
        event.addListener(sync);
        LOGGER.info("[Skein] added Sync button at y={}", y);
    }

    /** @param mcAddress the address the player tried, or {@code null} if unknown */
    private void startSync(String mcAddress) {
        SkeinProgressScreen screen = new SkeinProgressScreen(platform.relauncher(), platform.paths().gameDir());
        Minecraft.getInstance().setScreen(screen);
        SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(10)));
        RememberedEndpointCache remembered = new RememberedEndpointCache(platform.paths().skeinDir());
        boolean haveAddress = mcAddress != null && !mcAddress.isBlank();
        syncExecutor.execute(() -> {
            Optional<String> url = haveAddress
                    ? join.discover(mcAddress, Optional.empty())
                    : Optional.empty();
            // Auto-discovery (the conventional port) found nothing, but the player has
            // typed a working URL for this exact server before: try that before asking
            // again.
            if (url.isEmpty() && haveAddress) {
                url = remembered.get(mcAddress)
                        .flatMap(rememberedUrl -> join.discoverAt(rememberedUrl, Optional.empty()));
            }
            if (url.isPresent()) {
                LOGGER.info("[Skein] discovered {}, syncing", url.get());
                join.syncFrom(url.get(), Optional.empty(), screen);
            } else {
                // No address, or nothing answered: let the player type the URL. The
                // submit callback (on the game thread) re-runs the sync in the pool.
                String prefill = haveAddress
                        ? SkeinEndpoint.deriveFrom(mcAddress)
                        : "http://your-server-ip:25585";
                LOGGER.info("[Skein] auto-discovery failed (haveAddress={}); prompting for URL",
                        haveAddress);
                screen.requestManualUrl(prefill, typed -> syncExecutor.execute(() -> {
                    // Only remember a URL we've actually confirmed answers, and only
                    // against a known server address (nothing to key an unknown one by).
                    if (haveAddress && join.discoverAt(typed, Optional.empty()).isPresent()) {
                        remembered.put(mcAddress, typed);
                    }
                    join.syncFrom(typed, Optional.empty(), screen);
                }));
            }
        });
    }
}
