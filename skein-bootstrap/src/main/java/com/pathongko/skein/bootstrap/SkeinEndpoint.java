package com.pathongko.skein.bootstrap;

/**
 * Derives a Skein HTTP base URL from the Minecraft server address the player
 * tried to join. Convention: same host, Skein's HTTP port (default 25585).
 * Used by the disconnect-driven sync flow, which has no play-phase channel to
 * receive the endpoint from.
 */
public final class SkeinEndpoint {

    public static final int DEFAULT_SKEIN_PORT = 25585;

    private SkeinEndpoint() {
    }

    public static String deriveFrom(String mcAddress) {
        return deriveFrom(mcAddress, DEFAULT_SKEIN_PORT);
    }

    public static String deriveFrom(String mcAddress, int skeinPort) {
        return "http://" + hostForUrl(mcAddress) + ":" + skeinPort;
    }

    /** Host without URL brackets, suitable for socket connections. */
    public static String host(String address) {
        String s = requireAddress(address);
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("malformed IPv6 address: " + address);
            return s.substring(1, close);
        }
        int firstColon = s.indexOf(':');
        return firstColon >= 0 && firstColon == s.lastIndexOf(':') ? s.substring(0, firstColon) : s;
    }

    /** Minecraft port in host:port, defaulting to Minecraft's standard port. */
    public static int minecraftPort(String address) {
        String s = requireAddress(address);
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("malformed IPv6 address: " + address);
            if (close + 1 < s.length() && s.charAt(close + 1) == ':') {
                return parsePort(s.substring(close + 2));
            }
            return 25565;
        }
        int firstColon = s.indexOf(':');
        return firstColon >= 0 && firstColon == s.lastIndexOf(':')
                ? parsePort(s.substring(firstColon + 1)) : 25565;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port: " + value);
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
    }

    private static String hostForUrl(String address) {
        String host = host(address);
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    /** Host portion, bracketed for use in a URL when it is an IPv6 literal. */
    private static String requireAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("blank server address");
        }
        return address.trim();
    }
}
