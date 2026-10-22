package com.pathongko.skein.common.util;

/**
 * Pure, stateless helpers for working with hash strings.
 *
 * <p>Kept dependency-free so the core can use it without pulling in anything.
 */
public final class Hashes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    /** Encode raw bytes as lower-case hex. */
    public static String toHex(byte[] bytes) {
        Preconditions.requireNonNull(bytes, "bytes");
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** Decode a hex string into bytes. Rejects odd-length / non-hex input. */
    public static byte[] fromHex(String hex) {
        Preconditions.requireNonNull(hex, "hex");
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even: " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = digit(hex.charAt(i * 2));
            int lo = digit(hex.charAt(i * 2 + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** True if the string is non-empty and made entirely of hex digits. */
    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (digitOrMinusOne(s.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Constant-time comparison of two hex strings of equal length.
     *
     * <p>Guards against timing attacks when comparing secrets/tokens; for plain
     * hashes it is simply a safe default.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= Character.toLowerCase(a.charAt(i)) ^ Character.toLowerCase(b.charAt(i));
        }
        return diff == 0;
    }

    private static int digit(char c) {
        int d = digitOrMinusOne(c);
        if (d < 0) {
            throw new IllegalArgumentException("not a hex digit: '" + c + "'");
        }
        return d;
    }

    private static int digitOrMinusOne(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
