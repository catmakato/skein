// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.net;

import com.pathongko.skein.platform.net.PlayerRef;

// Forge 47.x networking imports (confirm exact packages on the build machine —
// Forge 47.3.x on 1.20.1 uses the classic SimpleChannel networking under
// net.minecraftforge.network. NeoForge forked this same API to
// net.neoforged.neoforge.network; the two are line-for-line equivalent here, only
// the package prefix differs):
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Owns the single Forge {@code SimpleChannel} used to transport Skein's control
 * packets. Every {@code net.minecraft.*} / {@code net.minecraftforge.*} type used
 * by the mod's networking is confined to this file (and the tiny
 * {@link ForgePlayerRef} it defines) — {@link ForgeControlChannel} never touches
 * Minecraft/Forge types directly.
 */
final class SkeinNetwork {

    // confirm on build machine: modid must match META-INF/mods.toml's modId ("skein")
    private static final String MOD_ID = "skein";
    private static final String PROTOCOL_VERSION = "1";

    // confirm on build machine: ResourceLocation(String, String) two-arg constructor
    // vs. the newer ResourceLocation.fromNamespaceAndPath(...) factory in this MC version
    private static final ResourceLocation CHANNEL_ID = new ResourceLocation(MOD_ID, "control");

    // confirm on build machine: NetworkRegistry.ChannelBuilder is the correct builder
    // entry point for SimpleChannel on Forge 47.x
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(CHANNEL_ID)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static volatile ForgeControlChannel channel;
    private static volatile boolean registered = false;

    private SkeinNetwork() {}

    /** Called once from the mod's setup to wire the channel to the given adapter. */
    static synchronized void register(ForgeControlChannel channel) {
        SkeinNetwork.channel = channel;
        if (registered) {
            return;
        }
        // confirm on build machine: registerMessage(int, Class, encoder, decoder, handler)
        // is the no-direction overload that accepts messages sent from either side on
        // this SimpleChannel version; if only the NetworkDirection-qualified
        // messageBuilder(...) overload exists, register this same handler for both
        // NetworkDirection.PLAY_TO_SERVER and NetworkDirection.PLAY_TO_CLIENT instead.
        CHANNEL.registerMessage(
                0,
                SkeinPayload.class,
                SkeinPayload::encode,
                SkeinPayload::decode,
                SkeinNetwork::handle
        );
        registered = true;
    }

    /** Client side: send a payload to the server. */
    static void sendToServer(byte[] payload) {
        CHANNEL.sendToServer(new SkeinPayload(payload));
    }

    /** Server side: send a payload to a specific player. */
    static void sendToPlayer(PlayerRef player, byte[] payload) {
        if (!(player instanceof ForgePlayerRef ref)) {
            throw new IllegalArgumentException(
                    "PlayerRef not produced by the Forge adapter: " + player);
        }
        // confirm on build machine: PacketDistributor.PLAYER.with(Supplier<ServerPlayer>)
        // is the correct single-player distributor on this Forge version
        CHANNEL.send(PacketDistributor.PLAYER.with(ref::player), new SkeinPayload(payload));
    }

    private static void handle(SkeinPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        // confirm on build machine: ctx.getSender() returns the ServerPlayer on the
        // server side and null on the client side for this SimpleChannel version
        ServerPlayer sender = ctx.getSender();
        ctx.enqueueWork(() -> {
            PlayerRef ref = sender == null ? null : new ForgePlayerRef(sender);
            ForgeControlChannel target = channel;
            if (target != null) {
                target.deliver(ref, message.payload());
            }
        });
        ctx.setPacketHandled(true);
    }

    /** The wire message: just carries the opaque payload bytes. */
    private static final class SkeinPayload {
        private final byte[] payload;

        SkeinPayload(byte[] payload) {
            this.payload = payload.clone();
        }

        byte[] payload() {
            return payload;
        }

        // confirm on build machine: FriendlyByteBuf.writeByteArray/readByteArray are
        // the correct encode/decode primitives for this MC version
        static void encode(SkeinPayload message, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeByteArray(message.payload);
        }

        static SkeinPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new SkeinPayload(buf.readByteArray());
        }
    }

    /** Wraps a {@code ServerPlayer} behind the loader-agnostic {@link PlayerRef}. */
    private static final class ForgePlayerRef implements PlayerRef {
        private final ServerPlayer player;

        ForgePlayerRef(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public String id() {
            return player.getUUID().toString();
        }

        ServerPlayer player() {
            return player;
        }
    }
}
