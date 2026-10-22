package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Preconditions;

/**
 * A path relative to the Minecraft instance root (e.g. {@code "mods/jei.jar"}).
 *
 * <p>This is the first line of defence against path-traversal / zip-slip: an
 * unsafe path is rejected at construction, so no manifest can ever produce one.
 * The {@code FileManager} performs a second, independent check before writing
 * (defence in depth).
 */
public record RelativePath(String value) {

    public RelativePath {
        Preconditions.requireNonBlank(value, "value");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new SecurityException("path must be relative: " + value);
        }
        if (normalized.contains("..")) {
            throw new SecurityException("path must not contain '..': " + value);
        }
        if (normalized.contains(":")) {
            throw new SecurityException("path must not contain a drive/scheme separator: " + value);
        }
        value = normalized;
    }

    /** The top-level segment, e.g. {@code "mods"} for {@code "mods/a/b.jar"}. */
    public String topSegment() {
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    public String fileName() {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    @Override
    public String toString() {
        return value;
    }
}
