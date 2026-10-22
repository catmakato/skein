package com.pathongko.skein.platform.net.packet;

import com.pathongko.skein.platform.net.PacketCodec;
import com.pathongko.skein.platform.net.PacketReader;
import com.pathongko.skein.platform.net.PacketWriter;

/**
 * Client -> Server, login phase. The client answers the server's handshake query:
 * whether it has the Skein mod, and its protocol/mod versions for the
 * compatibility check. See design doc, Part 3.
 */
public record HandshakeReplyC2S(boolean hasMod, int clientProtocolVersion, String clientModVersion) {

    public static final PacketCodec<HandshakeReplyC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(HandshakeReplyC2S p, PacketWriter w) {
            w.writeBoolean(p.hasMod())
                    .writeInt(p.clientProtocolVersion())
                    .writeString(p.clientModVersion());
        }

        @Override
        public HandshakeReplyC2S decode(PacketReader r) {
            boolean hasMod = r.readBoolean();
            int protocol = r.readInt();
            String version = r.readString();
            return new HandshakeReplyC2S(hasMod, protocol, version);
        }
    };
}
