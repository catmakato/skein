package com.pathongko.skein.platform.net;

import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The codec round-trip is the contract that lets Forge and NeoForge exchange the
 * same packets: encode -> bytes -> decode must reproduce the original exactly.
 */
class PacketCodecTest {

    @Test
    void handshakeReplyRoundTrips() {
        HandshakeReplyC2S original = new HandshakeReplyC2S(true, 100, "0.0.1-SNAPSHOT");
        byte[] wire = HandshakeReplyC2S.CODEC.toBytes(original);
        assertThat(HandshakeReplyC2S.CODEC.fromBytes(wire)).isEqualTo(original);
    }

    @Test
    void sessionGrantRoundTrips() {
        SessionGrantS2C original = SessionGrantS2C.granted(
                "http://127.0.0.1:25566/skein", "tok-abc123", "2026.07.05-3",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2");
        byte[] wire = SessionGrantS2C.CODEC.toBytes(original);
        assertThat(SessionGrantS2C.CODEC.fromBytes(wire)).isEqualTo(original);
    }

    @Test
    void sessionDenialRoundTrips() {
        SessionGrantS2C original = SessionGrantS2C.denied("You are not on this server's whitelist.");
        byte[] wire = SessionGrantS2C.CODEC.toBytes(original);
        SessionGrantS2C decoded = SessionGrantS2C.CODEC.fromBytes(wire);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.granted()).isFalse();
        assertThat(decoded.deniedReason()).isEqualTo("You are not on this server's whitelist.");
    }
}
