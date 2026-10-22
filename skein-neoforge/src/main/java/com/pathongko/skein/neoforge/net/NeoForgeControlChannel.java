// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.net;

import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;

/**
 * {@link ControlChannel} transport over one NeoForge {@code SimpleChannel}. A
 * single message type ({@code SkeinPayload}, registered by {@link SkeinNetwork})
 * carries an opaque {@code byte[]} — the shared packet codecs already bake an
 * opcode into every payload, so this layer never inspects the bytes.
 *
 * <p>This class is deliberately loader-free: every {@code net.minecraft.*} /
 * {@code net.neoforged.*} type lives in {@link SkeinNetwork} instead, so if a
 * NeoForge 47.x API name is off, only that file needs to change.
 */
public final class NeoForgeControlChannel implements ControlChannel {

    private volatile InboundHandler handler = (sender, payload) -> {};

    /**
     * Construction only builds the adapter; the mod's setup is responsible for
     * calling {@link #register()} once the NeoForge networking bus is ready to
     * accept channel registration.
     */
    public NeoForgeControlChannel() {
    }

    /**
     * Registers this channel with NeoForge's networking bus. The mod's setup
     * (Composition Root) calls this once, after construction, when the
     * networking bus is ready to accept channel registration.
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
