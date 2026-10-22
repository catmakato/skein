package com.pathongko.skein.platform.net;

/**
 * The transport for control packets, implemented by each loader adapter over its
 * own networking API (Forge's SimpleChannel, NeoForge's channel registrar, etc.).
 * Shared code only ever sees {@code byte[]} payloads produced by {@link PacketCodec}.
 *
 * <p>Inbound packets are delivered by the adapter to a handler the shared code
 * registers via {@link #onReceive}; the adapter is responsible for hopping to the
 * correct thread before invoking it.
 */
public interface ControlChannel {

    /** Client side: send a payload to the server. */
    void sendToServer(byte[] payload);

    /** Server side: send a payload to a specific player. */
    void sendToPlayer(PlayerRef player, byte[] payload);

    /** Register the shared handler for inbound payloads. */
    void onReceive(InboundHandler handler);

    @FunctionalInterface
    interface InboundHandler {
        /** @param sender the player on the server side, or null on the client side */
        void handle(PlayerRef sender, byte[] payload);
    }
}
