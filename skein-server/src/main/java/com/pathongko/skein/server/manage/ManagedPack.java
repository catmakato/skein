package com.pathongko.skein.server.manage;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.manifest.GeneratedPack;
import com.pathongko.skein.server.manifest.ManifestGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * A pack directory the admin can mutate at runtime. Holds the current
 * {@link GeneratedPack} and rebuilds it (rescans + rehashes) whenever files
 * change or the version is bumped. All mutating operations are synchronized so
 * the HTTP server always serves a consistent snapshot.
 */
public final class ManagedPack {

    private final Path packDir;
    private final String packId;
    private final HashAlgorithm algorithm;
    private final Map<FileCategory, String> categoryDirs;
    private final Set<String> managedDirNames;
    private final ManifestGenerator generator;

    private volatile PackVersion version;
    private volatile GeneratedPack current;

    public ManagedPack(Path packDir, String packId, PackVersion version, HashAlgorithm algorithm,
                       String fileBaseUrl, Map<FileCategory, String> categoryDirs,
                       HashService hashService, JsonManifestCodec codec) {
        this.packDir = Preconditions.requireNonNull(packDir, "packDir").toAbsolutePath().normalize();
        this.packId = Preconditions.requireNonBlank(packId, "packId");
        this.version = Preconditions.requireNonNull(version, "version");
        this.algorithm = Preconditions.requireNonNull(algorithm, "algorithm");
        this.categoryDirs = Map.copyOf(categoryDirs);
        this.managedDirNames = Set.copyOf(categoryDirs.values());
        this.generator = new ManifestGenerator(hashService, codec, algorithm, fileBaseUrl);
        ensureScaffold();
        regenerate();
    }

    /**
     * Create the pack directory and its managed category subdirectories (mods/,
     * config/, ...) if they don't exist yet, so an operator who points {@code
     * packDir} at a fresh path gets an empty, ready-to-fill layout to drop files
     * into. Existing files and directories are left untouched.
     */
    private void ensureScaffold() {
        try {
            Files.createDirectories(packDir);
            for (String dirName : managedDirNames) {
                Files.createDirectories(packDir.resolve(dirName));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed creating pack directory scaffold under " + packDir, e);
        }
    }

    public GeneratedPack current() {
        return current;
    }

    public Path packDir() {
        return packDir;
    }

    public PackVersion version() {
        return version;
    }

    /** Rescan the directory and rebuild the manifest. */
    public synchronized void regenerate() {
        this.current = generator.generate(packDir, packId, version, Instant.now(), categoryDirs);
    }

    /** Bump the pack version and regenerate. */
    public synchronized void setVersion(PackVersion newVersion) {
        this.version = Preconditions.requireNonNull(newVersion, "newVersion");
        regenerate();
    }

    /** Write (or overwrite) a file under a managed directory, then regenerate. */
    public synchronized void addFile(RelativePath relativePath, byte[] content) {
        addFile(relativePath, content, true);
    }

    /**
     * Write (or overwrite) a file under a managed directory. When {@code regenerate}
     * is false the manifest is left stale — used for bulk uploads, which write many
     * files and then call {@link #regenerate()} once at the end (so the pack is
     * rehashed once, not once per file).
     */
    public synchronized void addFile(RelativePath relativePath, byte[] content, boolean regenerate) {
        Path target = resolveManaged(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing " + relativePath, e);
        }
        if (regenerate) {
            regenerate();
        }
    }

    /** Delete a file under a managed directory, then regenerate. */
    public synchronized boolean deleteFile(RelativePath relativePath) {
        Path target = resolveManaged(relativePath);
        boolean removed;
        try {
            removed = Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("failed deleting " + relativePath, e);
        }
        if (removed) {
            regenerate();
        }
        return removed;
    }

    private Path resolveManaged(RelativePath relativePath) {
        if (!managedDirNames.contains(relativePath.topSegment())) {
            throw new SecurityException("path is not under a managed directory: " + relativePath);
        }
        Path resolved = packDir.resolve(relativePath.value()).normalize();
        if (!resolved.startsWith(packDir)) {
            throw new SecurityException("path escapes the pack directory: " + relativePath);
        }
        return resolved;
    }
}
