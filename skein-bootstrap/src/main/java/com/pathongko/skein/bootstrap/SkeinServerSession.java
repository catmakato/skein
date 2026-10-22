package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.platform.net.PlayerRef;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Server-side handshake logic: turn a client reply into a session grant. */
public final class SkeinServerSession {

    private final String transferEndpoint;
    private final Optional<String> token;
    private final Supplier<GeneratedPack> pack;
    private final HashService hash;
    private final BooleanSupplier whitelistGateEnabled;
    private final MinecraftWhitelist whitelist;

    public SkeinServerSession(String transferEndpoint, Optional<String> token,
                              Supplier<GeneratedPack> pack, HashService hash,
                              BooleanSupplier whitelistGateEnabled, MinecraftWhitelist whitelist) {
        this.transferEndpoint = Preconditions.requireNonBlank(transferEndpoint, "transferEndpoint");
        this.token = Preconditions.requireNonNull(token, "token");
        this.pack = Preconditions.requireNonNull(pack, "pack");
        this.hash = Preconditions.requireNonNull(hash, "hash");
        this.whitelistGateEnabled = Preconditions.requireNonNull(whitelistGateEnabled, "whitelistGateEnabled");
        this.whitelist = Preconditions.requireNonNull(whitelist, "whitelist");
    }

    public SessionGrantS2C onHandshake(PlayerRef sender, HandshakeReplyC2S reply) {
        Preconditions.requireNonNull(sender, "sender");
        Preconditions.requireNonNull(reply, "reply");

        if (reply.clientProtocolVersion() != ClientBootstrap.PROTOCOL_VERSION) {
            return deny(sender, "Your Skein mod version is out of date — please update and rejoin.");
        }

        if (whitelistGateEnabled.getAsBoolean() && !whitelist.contains(sender.id())) {
            return deny(sender, "You are not on this server's whitelist. Ask an admin to add you.");
        }

        GeneratedPack current = pack.get();
        String rootHash = hash.hash(current.encodedRoot(), HashAlgorithm.SHA256).value();
        return SessionGrantS2C.granted(
                transferEndpoint,
                token.orElse(""),
                current.root().packVersion().toString(),
                rootHash);
    }

    private static SessionGrantS2C deny(PlayerRef sender, String reason) {
        System.err.println("[Skein] handshake denied for " + sender.id() + ": " + reason);
        return SessionGrantS2C.denied(reason);
    }
}
