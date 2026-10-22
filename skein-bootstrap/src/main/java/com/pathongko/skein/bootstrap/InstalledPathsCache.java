package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the set of paths Skein itself installed as of the last successful
 * sync, under skeinDir. Read before diffing and written after a successful sync
 * (see {@link SkeinClientSession}) so {@code DiffOptions.safe} can tell a
 * Skein-installed file apart from one the player added themselves.
 */
public final class InstalledPathsCache {

    private static final String VERSION_MARKER = "# skein-ledger v1";

    private final Path file;

    public InstalledPathsCache(Path skeinDir) {
        Preconditions.requireNonNull(skeinDir, "skeinDir");
        this.file = skeinDir.resolve("installed-paths.txt");
    }

    /** Best-effort: a missing file, an unversioned file, or any parse failure yields an empty set. */
    public Set<RelativePath> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Set.of();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(VERSION_MARKER)) {
                return Set.of();
            }
            Set<RelativePath> paths = new LinkedHashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    paths.add(new RelativePath(line.trim()));
                }
            }
            return Set.copyOf(paths);
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * Best-effort: a write failure only costs an extra re-fetch/re-check next time, not
     * a session failure. Defensive against a path containing {@code \n} or {@code \r}
     * (which {@link RelativePath} does not itself reject): one bad entry would inject
     * extra lines into this one-path-per-line file, corrupting entries that follow it.
     * Rather than write a partially-sanitized file, the whole write is aborted and the
     * previous on-disk ledger (if any) is left untouched — "don't write anything this
     * round" is safer than "write something that might misparse."
     */
    public void write(Set<RelativePath> installedPaths) {
        Preconditions.requireNonNull(installedPaths, "installedPaths");
        boolean hasUnsafePath = installedPaths.stream()
                .map(RelativePath::value)
                .anyMatch(p -> p.indexOf('\n') >= 0 || p.indexOf('\r') >= 0);
        if (hasUnsafePath) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            StringBuilder content = new StringBuilder(VERSION_MARKER).append('\n');
            installedPaths.stream()
                    .map(RelativePath::value)
                    .sorted()
                    .forEach(p -> content.append(p).append('\n'));
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort cache; see javadoc above
        }
    }
}
