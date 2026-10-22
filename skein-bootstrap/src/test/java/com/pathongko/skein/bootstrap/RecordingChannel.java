package com.pathongko.skein.bootstrap;

import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;
import java.util.ArrayList;
import java.util.List;

/**
 * A test double for {@link ControlChannel} that records outbound payloads and
 * lets a test manually {@link #deliver} an inbound payload to whatever handler
 * was registered via {@link #onReceive}.
 */
final class RecordingChannel implements ControlChannel {

    final List<byte[]> toServer = new ArrayList<>();
    final List<SentToPlayer> toPlayer = new ArrayList<>();
    private InboundHandler handler;

    @Override
    public void sendToServer(byte[] payload) {
        toServer.add(payload);
    }

    @Override
    public void sendToPlayer(PlayerRef player, byte[] payload) {
        toPlayer.add(new SentToPlayer(player, payload));
    }

    @Override
    public void onReceive(InboundHandler handler) {
        this.handler = handler;
    }

    /** Invokes the handler registered via {@link #onReceive}, as the adapter would on delivery. */
    void deliver(PlayerRef sender, byte[] payload) {
        if (handler == null) {
            throw new IllegalStateException("no handler registered via onReceive");
        }
        handler.handle(sender, payload);
    }

    /** A payload sent to a specific player via {@link #sendToPlayer}. */
    record SentToPlayer(PlayerRef player, byte[] payload) {
    }
}
