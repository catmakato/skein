package com.pathongko.skein.neoforge.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads {@code skein-server.properties} from the dedicated server's config
 * directory and hands back a small, immutable settings bundle for
 * {@code SkeinMod}'s server-side startup.
 *
 * <p>Deliberately dependency-free (no {@code net.minecraft.*} / {@code
 * net.neoforged.*} imports, just {@link java.util.Properties}) so it needs no
 * NeoGradle toolchain to compile or unit-test — it happens to live in the
 * excluded {@code skein-neoforge} module only because that is where the
 * dedicated-server config directory is known; it could be moved to
 * {@code skein-bootstrap} later without change.
 *
 * <p>If the properties file is absent, {@link #load(Path)} writes a
 * commented template with sane defaults next to where it looked, and returns
 * those same defaults — so a server operator's first boot produces an
 * editable file rather than a silent default or a crash.
 */
public final class SkeinServerConfig {

    private static final String FILE_NAME = "skein-server.properties";
    private static final int DEFAULT_PORT = 25585;
    private static final String DEFAULT_ENDPOINT = "http://your-host:25585";
    // confirm on build machine: relative to the server config dir, the server's
    // root run directory (where mods/, config/, etc. live) is almost always the
    // parent of "config" — but this is a convention, not a guarantee, if a
    // launcher relocates directories. Operators can always override packDir.
    private static final String DEFAULT_PACK_DIR_RELATIVE = "..";

    private final Path packDir;
    private final int httpPort;
    private final Optional<String> clientToken;
    private final Optional<String> adminPassword;
    private final String publicEndpoint;

    private SkeinServerConfig(Path packDir, int httpPort, Optional<String> clientToken,
                              Optional<String> adminPassword, String publicEndpoint) {
        this.packDir = Objects.requireNonNull(packDir, "packDir");
        this.httpPort = httpPort;
        this.clientToken = Objects.requireNonNull(clientToken, "clientToken");
        this.adminPassword = Objects.requireNonNull(adminPassword, "adminPassword");
        this.publicEndpoint = Objects.requireNonNull(publicEndpoint, "publicEndpoint");
    }

    /**
     * Loads {@code skein-server.properties} from {@code configDir}. Writes a
     * template with defaults (and returns those defaults) if the file does not
     * exist yet.
     */
    public static SkeinServerConfig load(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            SkeinServerConfig defaults = defaults(configDir);
            writeTemplate(file, defaults);
            return defaults;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + file, e);
        }

        Path packDir = resolvePackDir(configDir, props.getProperty("packDir", DEFAULT_PACK_DIR_RELATIVE));
        int httpPort = parsePort(props.getProperty("httpPort"));
        Optional<String> clientToken = blankToEmpty(props.getProperty("clientToken", ""));
        Optional<String> adminPassword = blankToEmpty(props.getProperty("adminPassword", ""));
        String publicEndpoint = trimOrDefault(props.getProperty("publicEndpoint"), DEFAULT_ENDPOINT);

        return new SkeinServerConfig(packDir, httpPort, clientToken, adminPassword, publicEndpoint);
    }

    private static SkeinServerConfig defaults(Path configDir) {
        return new SkeinServerConfig(
                resolvePackDir(configDir, DEFAULT_PACK_DIR_RELATIVE),
                DEFAULT_PORT,
                Optional.empty(),
                Optional.empty(),
                DEFAULT_ENDPOINT);
    }

    private static Path resolvePackDir(Path configDir, String rawValue) {
        String value = rawValue == null || rawValue.isBlank() ? DEFAULT_PACK_DIR_RELATIVE : rawValue.trim();
        return configDir.resolve(value).normalize();
    }

    private static int parsePort(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static Optional<String> blankToEmpty(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static String trimOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void writeTemplate(Path file, SkeinServerConfig defaults) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("failed creating " + file.getParent(), e);
        }
        String template = """
                # Skein server configuration. Edit and restart the server to apply.
                #
                # packDir: directory containing the managed mods/, config/, resourcepacks/,
                #          shaderpacks/ and datapacks/ subfolders that Skein serves to clients.
                #          Defaults to the server's root run directory (one level above this
                #          config folder). Relative paths are resolved against this file's
                #          directory; absolute paths work too.
                packDir=%s

                # httpPort: the port Skein's built-in file server listens on.
                httpPort=%d

                # publicEndpoint: the URL clients use to reach this server's Skein HTTP
                #          endpoint. MUST be an address clients can actually resolve/reach
                #          (your public IP/hostname + httpPort) -- "localhost" only works for
                #          a client running on the same machine as the server.
                publicEndpoint=%s

                # clientToken: optional bearer token clients must present to sync. Leave blank
                #          to allow unauthenticated sync (fine for a private/whitelisted server).
                #          If you enable the whitelist gate (see the admin dashboard), you should
                #          also set a clientToken here -- otherwise a denied player can still fetch
                #          the whole pack via the client's HTTP fallback sync, which does not check
                #          the whitelist.
                clientToken=

                # adminPassword: optional password for the web admin dashboard at /admin. Leave
                #          blank to disable the dashboard entirely.
                adminPassword=
                """.formatted(DEFAULT_PACK_DIR_RELATIVE, defaults.httpPort, defaults.publicEndpoint);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(template.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing template " + file, e);
        }
    }

    public Path packDir() {
        return packDir;
    }

    public int httpPort() {
        return httpPort;
    }

    public Optional<String> clientToken() {
        return clientToken;
    }

    public Optional<String> adminPassword() {
        return adminPassword;
    }

    public String publicEndpoint() {
        return publicEndpoint;
    }
}
