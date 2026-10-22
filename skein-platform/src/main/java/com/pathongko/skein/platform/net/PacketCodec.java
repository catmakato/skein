package com.pathongko.skein.platform.net;

/**
 * Encodes/decodes one control-packet type to and from bytes. Keeping the codec
 * separate from the packet record (and from any loader) means the exact same
 * serialization runs on Forge, NeoForge and in unit tests.
 */
public interface PacketCodec<T> {

    void encode(T packet, PacketWriter writer);

    T decode(PacketReader reader);

    default byte[] toBytes(T packet) {
        PacketWriter w = new PacketWriter();
        encode(packet, w);
        return w.toByteArray();
    }

    default T fromBytes(byte[] payload) {
        return decode(new PacketReader(payload));
    }
}
