package com.pathongko.skein.platform.net;

import com.pathongko.skein.common.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Reads control-packet fields written by {@link PacketWriter}. The field order
 * must mirror the writer exactly (that symmetry is what the codec round-trip
 * tests guard).
 */
public final class PacketReader {

    private final DataInputStream in;

    public PacketReader(byte[] payload) {
        Preconditions.requireNonNull(payload, "payload");
        this.in = new DataInputStream(new ByteArrayInputStream(payload));
    }

    public boolean readBoolean() {
        return run(in::readBoolean);
    }

    public int readInt() {
        return run(in::readInt);
    }

    public long readLong() {
        return run(in::readLong);
    }

    public String readString() {
        return run(in::readUTF);
    }

    private <T> T run(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new UncheckedIOException("malformed packet payload", e);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
