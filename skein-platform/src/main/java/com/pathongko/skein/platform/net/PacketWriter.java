package com.pathongko.skein.platform.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes control-packet fields into a plain {@code byte[]}. Using our own format
 * (not Minecraft's {@code FriendlyByteBuf}) is what keeps packet definitions in
 * the shared layer: every loader adapter simply wraps/unwraps this byte array in
 * its own network type. Backed by {@link DataOutputStream}, so the wire format is
 * well-defined and portable.
 */
public final class PacketWriter {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    public PacketWriter writeBoolean(boolean v) {
        return run(() -> out.writeBoolean(v));
    }

    public PacketWriter writeInt(int v) {
        return run(() -> out.writeInt(v));
    }

    public PacketWriter writeLong(long v) {
        return run(() -> out.writeLong(v));
    }

    public PacketWriter writeString(String v) {
        return run(() -> out.writeUTF(v)); // length-prefixed UTF-8
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }

    private PacketWriter run(IoAction action) {
        try {
            action.run();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
