package com.pathongko.skein.app.local;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.diff.LocalIndex;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.manifest.ResolvedManifest;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.port.IncrementalHasher;
import com.pathongko.skein.core.sync.port.LocalStateProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the {@link LocalIndex} by hashing every file currently under the managed
 * directories. This is the straightforward implementation; a production version
 * would consult the fingerprint (size+mtime) cache to skip re-hashing unchanged
 * files (design doc, Part 2).
 */
public final class FileSystemLocalStateProvider implements LocalStateProvider {

    private final Path root;
    private final HashService hashService;
    private final HashAlgorithm algorithm;
    private final Set<String> managedDirs;

    public FileSystemLocalStateProvider(Path root, HashService hashService,
                                        HashAlgorithm algorithm, Collection<String> managedDirs) {
        this.root = Preconditions.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.hashService = Preconditions.requireNonNull(hashService, "hashService");
        this.algorithm = Preconditions.requireNonNull(algorithm, "algorithm");
        this.managedDirs = Set.copyOf(managedDirs);
    }

    @Override
    public LocalIndex capture(ResolvedManifest desired) {
        Map<RelativePath, FileHash> hashes = new LinkedHashMap<>();
        for (String dirName : managedDirs) {
            Path dir = root.resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            hashDirectory(dir, hashes);
        }
        return new LocalIndex(hashes);
    }

    private void hashDirectory(Path dir, Map<RelativePath, FileHash> hashes) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                RelativePath rel = new RelativePath(root.relativize(file).toString());
                hashes.put(rel, hashFile(file));
            }
        } catch (IOException e) {
            throw new SkeinException(ErrorCode.LOCAL_IO, Recoverability.NEEDS_USER,
                    "cannot scan " + dir, e);
        }
    }

    private FileHash hashFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            IncrementalHasher hasher = hashService.newHasher(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                hasher.update(buffer, 0, n);
            }
            return hasher.finish();
        } catch (IOException e) {
            throw new SkeinException(ErrorCode.LOCAL_IO, Recoverability.NEEDS_USER,
                    "cannot hash " + file, e);
        }
    }
}
