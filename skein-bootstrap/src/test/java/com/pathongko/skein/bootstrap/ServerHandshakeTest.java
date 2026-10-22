package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerHandshakeTest {

    private static SkeinServerSession sessionWithGate(GeneratedPack pack, boolean gateEnabled,
                                                       MinecraftWhitelist whitelist) {
        return new SkeinServerSession("http://example:25585", Optional.of("tok"), () -> pack,
                new MessageDigestHashService(), () -> gateEnabled, whitelist);
    }

    @Test
    void grantCarriesEndpointAndRootHashWhenGateIsOff() {
        GeneratedPack pack = TestPacks.smallPack(); // helper building a GeneratedPack
        var hash = new MessageDigestHashService();
        SkeinServerSession session = sessionWithGate(pack, false, whitelistThatDenies());

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, ClientBootstrap.PROTOCOL_VERSION, "0.0.1"));

        assertThat(grant.granted()).isTrue();
        assertThat(grant.transferEndpoint()).isEqualTo("http://example:25585");
        assertThat(grant.sessionToken()).isEqualTo("tok");
        assertThat(grant.packVersion()).isEqualTo(pack.root().packVersion().toString());
        assertThat(grant.rootManifestHash())
                .isEqualTo(hash.hash(pack.encodedRoot(), HashAlgorithm.SHA256).value());
    }

    @Test
    void gateOnAndWhitelistedPlayerIsGranted(@TempDir Path dir) throws Exception {
        GeneratedPack pack = TestPacks.smallPack();
        Path whitelistFile = dir.resolve("whitelist.json");
        Files.writeString(whitelistFile, "[{\"uuid\":\"p1\",\"name\":\"Steve\"}]");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(whitelistFile));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, ClientBootstrap.PROTOCOL_VERSION, "0.0.1"));

        assertThat(grant.granted()).isTrue();
    }

    @Test
    void gateOnAndUnlistedPlayerIsDenied(@TempDir Path dir) throws Exception {
        GeneratedPack pack = TestPacks.smallPack();
        Path whitelistFile = dir.resolve("whitelist.json");
        Files.writeString(whitelistFile, "[{\"uuid\":\"someone-else\",\"name\":\"Alex\"}]");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(whitelistFile));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, ClientBootstrap.PROTOCOL_VERSION, "0.0.1"));

        assertThat(grant.granted()).isFalse();
        assertThat(grant.deniedReason()).isNotBlank();
    }

    @Test
    void gateOnAndUnreadableWhitelistFailsClosed(@TempDir Path dir) {
        GeneratedPack pack = TestPacks.smallPack();
        Path missing = dir.resolve("does-not-exist.json");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(missing));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, ClientBootstrap.PROTOCOL_VERSION, "0.0.1"));

        assertThat(grant.granted()).isFalse();
    }

    @Test
    void staleClientProtocolVersionIsDeniedBeforeTheWhitelistGate() {
        GeneratedPack pack = TestPacks.smallPack();
        // Gate is off here on purpose: the version check must fire regardless of
        // the whitelist gate's state, since a version mismatch is more
        // fundamental than the whitelist decision.
        SkeinServerSession session = sessionWithGate(pack, false, whitelistThatDenies());
        int staleVersion = ClientBootstrap.PROTOCOL_VERSION - 1;

        SessionGrantS2C grant = session.onHandshake(
                new NamedPlayer("p1"), new HandshakeReplyC2S(true, staleVersion, "0.0.1"));

        assertThat(grant.granted()).isFalse();
        assertThat(grant.deniedReason()).isNotBlank();
    }

    @Test
    void bootstrapRepliesToSender() {
        GeneratedPack pack = TestPacks.smallPack();
        RecordingChannel channel = new RecordingChannel();
        SkeinServerSession session = sessionWithGate(pack, false, whitelistThatDenies());
        new ServerBootstrap(channel, session).install();

        channel.deliver(new NamedPlayer("p1"),
                HandshakeReplyC2S.CODEC.toBytes(new HandshakeReplyC2S(true, ClientBootstrap.PROTOCOL_VERSION, "0.0.1")));

        assertThat(channel.toPlayer).hasSize(1);
        SessionGrantS2C grant = SessionGrantS2C.CODEC.fromBytes(channel.toPlayer.get(0).payload());
        assertThat(grant.transferEndpoint()).isEqualTo("http://example:25585");
    }

    private static MinecraftWhitelist whitelistThatDenies() {
        // A whitelist backed by a file that doesn't exist: MinecraftWhitelist.contains
        // fails closed (false) for it, same as it would for a genuinely unlisted
        // player. Used only in gate-off tests, where the grant must be true
        // regardless of what the whitelist would say.
        return new MinecraftWhitelist(Path.of("nonexistent-whitelist.json"));
    }
}
