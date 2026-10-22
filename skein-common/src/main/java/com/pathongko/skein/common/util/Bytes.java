package com.pathongko.skein.common.util;

/**
 * Small byte/size helpers. Pure and stateless.
 */
public final class Bytes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private Bytes() {
    }

    /** Human-readable size using binary (1024) units, e.g. 20000000000L -> "18.6 GB". */
    public static String humanReadable(long bytes) {
        Preconditions.requireNonNegative(bytes, "bytes");
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, UNITS[unit]);
    }
}
