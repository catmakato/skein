package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.concurrent.Executor;

/**
 * Client-side entry point the loader adapter calls. Registers the inbound handler
 * that turns a {@link SessionGrantS2C} into a background {@link SkeinClientSession}
 * run, and can send the handshake reply.
 */
public final class ClientBootstrap {

    public static final int PROTOCOL_VERSION = 2;

    private final ClientPlatform platform;
    private final Executor syncExecutor;
    private final SyncProgressListener listener;

    public ClientBootstrap(ClientPlatform platform, Executor syncExecutor,
                           SyncProgressListener listener) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.syncExecutor = Preconditions.requireNonNull(syncExecutor, "syncExecutor");
        this.listener = Preconditions.requireNonNull(listener, "listener");
    }

    public void install() {
        ControlChannel channel = platform.controlChannel();
        channel.onReceive((sender, payload) -> {
            SessionGrantS2C grant = SessionGrantS2C.CODEC.fromBytes(payload);
            if (!grant.granted()) {
                reportDenial(grant.deniedReason());
                return;
            }
            syncExecutor.execute(() -> new SkeinClientSession(platform, listener).run(grant));
        });
    }

    private void reportDenial(String reason) {
        SyncOutcome denied = new SyncOutcome.Failed(ErrorCode.ACCESS_DENIED, Recoverability.NEEDS_ADMIN, reason);
        try {
            listener.onEvent(new SyncEvent.SyncFinished(denied));
        } catch (RuntimeException ignored) {
            // listener is display code; its failure must not break this handler
        }
    }

    public void sendHandshakeReply() {
        String modVersion = platform.loaderInfo().skeinVersion();
        HandshakeReplyC2S reply = new HandshakeReplyC2S(true, PROTOCOL_VERSION, modVersion);
        platform.controlChannel().sendToServer(HandshakeReplyC2S.CODEC.toBytes(reply));
    }
}
