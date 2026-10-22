package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.json.Json;
import com.pathongko.skein.common.util.Preconditions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads a vanilla Minecraft {@code whitelist.json} to check whether a player UUID
 * is present. Re-reads the file on every check — it is tiny and this only runs
 * once per join attempt, so live edits (an admin running {@code /whitelist add})
 * take effect immediately with no caching/invalidation to get wrong.
 *
 * <p>Any read or parse failure is treated as "not whitelisted" (fail closed):
 * {@code SkeinServerSession} uses that the same way as a genuine non-member,
 * denying access rather than silently falling back to "allow everyone".
 */
public final class MinecraftWhitelist {

    private final Path file;

    public MinecraftWhitelist(Path file) {
        this.file = Preconditions.requireNonNull(file, "file");
    }

    public boolean contains(String uuid) {
        Preconditions.requireNonNull(uuid, "uuid");
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed = Json.read(text);
            if (!(parsed instanceof List<?> entries)) {
                return false;
            }
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map
                        && uuid.equalsIgnoreCase(String.valueOf(map.get("uuid")))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Skein] could not read whitelist " + file + ": " + e.getMessage());
            return false;
        }
    }
}
