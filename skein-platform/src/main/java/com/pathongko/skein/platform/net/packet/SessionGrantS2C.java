package com.pathongko.skein.platform.net.packet;

import com.pathongko.skein.platform.net.PacketCodec;
import com.pathongko.skein.platform.net.PacketReader;
import com.pathongko.skein.platform.net.PacketWriter;

/**
 * Server -> Client, login phase. If the handshake is compatible AND the player is
 * allowed to sync (see the whitelist gate on {@code SkeinServerSession}), the
 * server hands the client its "ticket": where the HTTP transfer endpoint is, a
 * short-lived session token, and the current pack version + root-manifest hash so
 * the client can compare against its cache. If not, {@code granted} is false and
 * only {@code deniedReason} is meaningful — the other fields are blank. See
 * design doc, Part 3, and docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md.
 */
public record SessionGrantS2C(
        boolean granted,
        String transferEndpoint,
        String sessionToken,
        String packVersion,
        String rootManifestHash,
        String deniedReason) {

    public static SessionGrantS2C granted(String transferEndpoint, String sessionToken,
                                          String packVersion, String rootManifestHash) {
        return new SessionGrantS2C(true, transferEndpoint, sessionToken, packVersion, rootManifestHash, "");
    }

    public static SessionGrantS2C denied(String reason) {
        return new SessionGrantS2C(false, "", "", "", "", reason);
    }

    public static final PacketCodec<SessionGrantS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(SessionGrantS2C p, PacketWriter w) {
            w.writeBoolean(p.granted())
                    .writeString(p.transferEndpoint())
                    .writeString(p.sessionToken())
                    .writeString(p.packVersion())
                    .writeString(p.rootManifestHash())
                    .writeString(p.deniedReason());
        }

        @Override
        public SessionGrantS2C decode(PacketReader r) {
            return new SessionGrantS2C(
                    r.readBoolean(),
                    r.readString(),
                    r.readString(),
                    r.readString(),
                    r.readString(),
                    r.readString());
        }
    };
}
