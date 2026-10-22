// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.net;

import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;

/**
 * {@link ControlChannel} transport over one Forge {@code SimpleChannel}. A single
 * message type ({@code SkeinPayload}, registered by {@link SkeinNetwork}) carries
 * an opaque {@code byte[]} — the shared packet codecs already bake an opcode into
 * every payload, so this layer never inspects the bytes.
 *
 * <p>This class is deliberately loader-free: every {@code net.minecraft.*} /
 * {@code net.minecraftforge.*} type lives in {@link SkeinNetwork} instead, so if a
 * Forge 47.x API name is off, only that file needs to change.
 */
public final class ForgeControlChannel implements ControlChannel {

    private volatile InboundHandler handler = (sender, payload) -> {};

    /**
     * Construction only builds the adapter; the mod's setup is responsible for
     * calling {@link #register()} once the Forge networking bus is ready to accept
     * channel registration.
     */
    public ForgeControlChannel() {
    }

    /**
     * Registers this channel with Forge's networking bus. The mod's setup
     * (Composition Root) calls this once, after construction, when the networking
     * bus is ready to accept channel registration.
     */
    public void register() {
        SkeinNetwork.register(this);
    }

    @Override
    public void sendToServer(byte[] payload) {
        SkeinNetwork.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(PlayerRef player, byte[] payload) {
        SkeinNetwork.sendToPlayer(player, payload);
    }

    @Override
    public void onReceive(InboundHandler handler) {
        this.handler = handler;
    }

    /**
     * Invoked by {@link SkeinNetwork}'s receive handler, which has already hopped
     * to the main thread via {@code ctx.enqueueWork(...)} before calling this.
     *
     * @param sender the player on the server side, or {@code null} on the client side
     */
    void deliver(PlayerRef sender, byte[] payload) {
        handler.handle(sender, payload);
    }
}
