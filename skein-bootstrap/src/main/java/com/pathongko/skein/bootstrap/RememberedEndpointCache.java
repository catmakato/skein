package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Remembers, per Minecraft server address, the Skein URL a player last typed
 * into the manual-entry fallback (see {@code SkeinProgressScreen}) so they are
 * only ever asked once per server. Read before offering the manual-URL prompt
 * and written after a URL from it is confirmed reachable.
 */
public final class RememberedEndpointCache {

    private static final String VERSION_MARKER = "# skein-endpoints v1";

    private final Path file;

    public RememberedEndpointCache(Path skeinDir) {
        Preconditions.requireNonNull(skeinDir, "skeinDir");
        this.file = skeinDir.resolve("remembered-endpoints.txt");
    }

    /** Best-effort: a missing file, an unversioned file, or any parse failure yields empty. */
    public Optional<String> get(String mcAddress) {
        Preconditions.requireNonNull(mcAddress, "mcAddress");
        return Optional.ofNullable(readAll().get(mcAddress));
    }

    /**
     * Remembers {@code url} for {@code mcAddress}, preserving every other
     * previously-remembered address. Best-effort, same fail-safe contract as
     * {@link InstalledPathsCache#write}: if either value would corrupt the
     * one-entry-per-line file, the whole write is aborted and the file already on
     * disk (if any) is left untouched.
     */
    public void put(String mcAddress, String url) {
        Preconditions.requireNonNull(mcAddress, "mcAddress");
        Preconditions.requireNonNull(url, "url");
        if (hasUnsafeChar(mcAddress) || hasUnsafeChar(url)) {
            return;
        }
        Map<String, String> entries = new LinkedHashMap<>(readAll());
        entries.put(mcAddress, url);
        try {
            Files.createDirectories(file.getParent());
            StringBuilder content = new StringBuilder(VERSION_MARKER).append('\n');
            new TreeMap<>(entries).forEach((k, v) -> content.append(k).append('=').append(v).append('\n'));
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort cache; see javadoc above
        }
    }

    private static boolean hasUnsafeChar(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private Map<String, String> readAll() {
        try {
            if (!Files.isRegularFile(file)) {
                return Map.of();
            }
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(VERSION_MARKER)) {
                return Map.of();
            }
            Map<String, String> entries = new LinkedHashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                entries.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
