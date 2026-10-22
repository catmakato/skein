package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;

/** Registers the server-side handshake handler on a ControlChannel. */
public final class ServerBootstrap {

    private final ControlChannel channel;
    private final SkeinServerSession session;

    public ServerBootstrap(ControlChannel channel, SkeinServerSession session) {
        this.channel = Preconditions.requireNonNull(channel, "channel");
        this.session = Preconditions.requireNonNull(session, "session");
    }

    public void install() {
        channel.onReceive((sender, payload) -> {
            HandshakeReplyC2S reply = HandshakeReplyC2S.CODEC.fromBytes(payload);
            SessionGrantS2C grant = session.onHandshake(sender, reply);
            channel.sendToPlayer(sender, SessionGrantS2C.CODEC.toBytes(grant));
        });
    }
}
