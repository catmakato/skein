package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Persists the root-manifest hash of the last successful sync under skeinDir. */
public final class RootHashCache {

    private final Path file;

    public RootHashCache(Path skeinDir) {
        Preconditions.requireNonNull(skeinDir, "skeinDir");
        this.file = skeinDir.resolve("root.hash");
    }

    public Optional<String> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void write(String rootHash) {
        Preconditions.requireNonBlank(rootHash, "rootHash");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rootHash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort cache; a write failure only costs a redundant diff next time
        }
    }
}
