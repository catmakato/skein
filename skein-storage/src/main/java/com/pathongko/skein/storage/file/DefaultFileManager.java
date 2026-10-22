package com.pathongko.skein.storage.file;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.port.CommitReceipt;
import com.pathongko.skein.core.port.FileManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * {@link FileManager} over {@code java.nio}. Staging lives under
 * {@code <root>/.skein/downloads} — deliberately on the same filesystem as the
 * targets so the final {@link StandardCopyOption#ATOMIC_MOVE} is genuinely atomic.
 * Replaced/extra files go to {@code <root>/.skein/quarantine/<batch>/} rather than
 * being deleted, so a commit is reversible.
 */
public final class DefaultFileManager implements FileManager {

    private final Path root;
    private final Path stagingRoot;
    private final Path quarantineRoot;
    private static final AtomicLong batchCounter = new AtomicLong();
    private static final Pattern BATCH_NAME = Pattern.compile("\\d+-\\d+");
    private static final String ADDED_FILES_MARKER = ".skein-added-files";
    private volatile String activeBatch;

    public DefaultFileManager(Path root) {
        this.root = Preconditions.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.stagingRoot = this.root.resolve(".skein").resolve("downloads");
        this.quarantineRoot = this.root.resolve(".skein").resolve("quarantine");
    }

    /** Group all quarantined files from one apply operation into a recoverable batch. */
    public synchronized void beginBackupBatch() {
        if (activeBatch != null) throw new IllegalStateException("a backup batch is already active");
        activeBatch = newBatchName();
    }

    public synchronized void endBackupBatch() {
        activeBatch = null;
    }

    public boolean hasBackupBatch() {
        return latestBatch().isPresent();
    }

    /** Restore the latest batch, moving the current versions into a fresh backup first. */
    public int restoreLatestBatch() {
        Path batch = latestBatch().orElseThrow(() -> new IllegalStateException("no Skein backup is available"));
        List<RelativePath> addedFiles = readAddedFiles(batch);
        List<Path> backupFiles = new ArrayList<>();
        try {
            Files.walkFileTree(batch, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) backupFiles.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Skein backup " + batch, e);
        }

        int restored = 0;
        for (RelativePath added : addedFiles) {
            Path live = resolveUnder(root, added);
            if (Files.exists(live)) {
                moveToQuarantine(added, live);
                restored++;
            }
        }
        for (Path backup : backupFiles) {
            RelativePath target = new RelativePath(batch.relativize(backup).toString());
            if (!isManagedCategory(target.topSegment())) continue;
            Path live = resolveUnder(root, target);
            createParentDirs(live);
            if (Files.exists(live)) moveToQuarantine(target, live);
            atomicMove(backup, live);
            restored++;
        }
        try {
            Files.deleteIfExists(batch.resolve(ADDED_FILES_MARKER));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot finish restoring backup " + batch, e);
        }
        deleteEmptyDirectories(batch);
        return restored;
    }

    private static List<RelativePath> readAddedFiles(Path batch) {
        Path marker = batch.resolve(ADDED_FILES_MARKER);
        if (!Files.isRegularFile(marker)) return List.of();
        try {
            return Files.readAllLines(marker).stream().filter(line -> !line.isBlank())
                    .map(RelativePath::new).filter(path -> isManagedCategory(path.topSegment())).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read added-file backup list " + marker, e);
        }
    }

    private Optional<Path> latestBatch() {
        if (!Files.isDirectory(quarantineRoot)) return Optional.empty();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(quarantineRoot)) {
            Path latest = null;
            long latestTime = Long.MIN_VALUE;
            long latestSequence = Long.MIN_VALUE;
            for (Path entry : entries) {
                if (!Files.isDirectory(entry)) continue;
                String name = entry.getFileName().toString();
                if (!BATCH_NAME.matcher(name).matches()) continue;
                try (var files = Files.walk(entry)) {
                    if (files.noneMatch(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                        continue;
                    }
                }
                int separator = name.indexOf('-');
                long timestamp = Long.parseLong(name.substring(0, separator));
                long sequence = Long.parseLong(name.substring(separator + 1));
                if (timestamp > latestTime || (timestamp == latestTime && sequence > latestSequence)) {
                    latest = entry;
                    latestTime = timestamp;
                    latestSequence = sequence;
                }
            }
            return Optional.ofNullable(latest);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean isManagedCategory(String category) {
        return category.equals("mods") || category.equals("config") || category.equals("resourcepacks")
                || category.equals("shaderpacks") || category.equals("datapacks");
    }

    private static void deleteEmptyDirectories(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    try (var children = Files.list(dir)) {
                        if (children.findAny().isEmpty()) Files.deleteIfExists(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // An empty backup directory is harmless and will be pruned later.
        }
    }

    @Override
    public Path stagingFileFor(RelativePath target) {
        Path staging = stagingPath(target);
        createParentDirs(staging);
        return staging;
    }

    @Override
    public CommitReceipt commit(RelativePath target) {
        Path staging = stagingPath(target);
        if (!Files.exists(staging)) {
            throw new IllegalStateException("nothing staged for " + target);
        }
        Path finalTarget = resolveUnder(root, target);
        createParentDirs(finalTarget);

        boolean existedBefore = Files.exists(finalTarget);
        if (!existedBefore && activeBatch != null) recordAddedFile(target);
        Optional<Path> quarantined = existedBefore
                ? Optional.of(moveToQuarantine(target, finalTarget))
                : Optional.empty();

        atomicMove(staging, finalTarget);
        return new CommitReceipt(target, finalTarget, quarantined);
    }

    @Override
    public Optional<Path> quarantine(RelativePath target) {
        Path live = resolveUnder(root, target);
        if (!Files.exists(live)) {
            return Optional.empty();
        }
        return Optional.of(moveToQuarantine(target, live));
    }

    /**
     * Best-effort maintenance: permanently deletes quarantine batches older than
     * {@code maxAge}. Never throws — a failure pruning one batch, or listing
     * {@link #quarantineRoot} at all, is swallowed so it can't block the caller's
     * sync or the rest of the batches. An entry that doesn't match the
     * {@code <epochMillis>-<counter>} naming convention this class itself writes
     * (in {@link #moveToQuarantine}), whose epoch prefix isn't a parseable long,
     * or whose real location resolves outside {@link #quarantineRoot} at any
     * depth — including nested inside an otherwise-legitimate batch (e.g. a
     * symlink, or on Windows a directory junction, which
     * {@link Files#isSymbolicLink} does not detect) — is left untouched at that
     * point and never recursed into; this method only ever removes things it
     * recognizes as its own.
     */
    public void pruneQuarantine(Instant now, Duration maxAge) {
        if (!Files.isDirectory(quarantineRoot)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(quarantineRoot)) {
            for (Path entry : entries) {
                pruneBatchIfExpired(entry, now, maxAge);
            }
        } catch (IOException | RuntimeException e) {
            // best-effort maintenance; try again next session
        }
    }

    private void pruneBatchIfExpired(Path entry, Instant now, Duration maxAge) {
        String name = entry.getFileName().toString();
        if (!BATCH_NAME.matcher(name).matches()) {
            return;
        }
        long epochMillis;
        try {
            epochMillis = Long.parseLong(name.substring(0, name.indexOf('-')));
        } catch (NumberFormatException e) {
            return;
        }
        if (now.toEpochMilli() - epochMillis <= maxAge.toMillis()) {
            return;
        }
        try {
            if (Files.isSymbolicLink(entry)) {
                return;
            }
            Path allowedRealRoot = quarantineRoot.toRealPath();
            if (!entry.toRealPath().startsWith(allowedRealRoot)) {
                return;
            }
            deleteRecursively(entry, allowedRealRoot);
        } catch (IOException | RuntimeException e) {
            // best-effort; try again next session
        }
    }

    /**
     * Deletes {@code dir} and everything under it, but never enters a
     * directory-shaped entry whose real (symlink/junction-resolved) location is
     * outside {@code allowedRealRoot} — a Windows directory junction planted
     * inside an otherwise-legitimate batch has {@link Files#isSymbolicLink}
     * return {@code false}, so the top-level check in {@link
     * #pruneBatchIfExpired} alone can't catch one nested deeper; this re-checks
     * at every directory level instead. Deleting the junction/symlink entry
     * itself (rather than descending into it) is always safe — removing a link
     * never touches what it points to, only entering it and deleting its
     * contents would. Best-effort throughout: any failure (a locked file, a
     * vanished path) is swallowed and left for a later prune pass.
     */
    private static void deleteRecursively(Path dir, Path allowedRealRoot) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) {
                    if (isUnderAllowedRoot(subdir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Escaping directory-shaped entry (symlink-to-dir, or a
                    // junction): remove just this link/reparse-point node --
                    // never its target -- and never recurse into it.
                    deleteQuietly(subdir);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Deleting a file-classified path -- including a symlink to
                    // a file -- only ever unlinks that exact directory entry; it
                    // never follows the link to delete a target elsewhere. No
                    // real-path check needed here.
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // best-effort; leave it for a later pass
                }

                @Override
                public FileVisitResult postVisitDirectory(Path subdir, IOException exc) {
                    deleteQuietly(subdir);
                    return FileVisitResult.CONTINUE;
                }

                private boolean isUnderAllowedRoot(Path path) {
                    try {
                        return path.toRealPath().startsWith(allowedRealRoot);
                    } catch (IOException e) {
                        return false;
                    }
                }
            });
        } catch (IOException e) {
            // best-effort; try again next session
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            // best-effort; leave it for a later prune pass
        }
    }

    @Override
    public void rollback(CommitReceipt receipt) {
        try {
            Files.deleteIfExists(receipt.committedTarget());
        } catch (IOException e) {
            throw new UncheckedIOException("rollback: cannot remove " + receipt.committedTarget(), e);
        }
        if (receipt.quarantinedPrevious().isPresent()) {
            createParentDirs(receipt.committedTarget());
            atomicMove(receipt.quarantinedPrevious().get(), receipt.committedTarget());
        }
    }

    // --- internals -----------------------------------------------------------

    private Path stagingPath(RelativePath target) {
        return resolveUnder(stagingRoot, target).resolveSibling(fileName(target) + ".part");
    }

    private static String fileName(RelativePath target) {
        return target.fileName();
    }

    private Path moveToQuarantine(RelativePath target, Path live) {
        String batch = activeBatch == null ? newBatchName() : activeBatch;
        Path dest = resolveUnder(quarantineRoot.resolve(batch), target);
        createParentDirs(dest);
        atomicMove(live, dest);
        return dest;
    }

    private void recordAddedFile(RelativePath target) {
        Path marker = quarantineRoot.resolve(activeBatch).resolve(ADDED_FILES_MARKER);
        createParentDirs(marker);
        try {
            Files.writeString(marker, target.value() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot record new file for backup: " + target, e);
        }
    }

    private static String newBatchName() {
        return System.currentTimeMillis() + "-" + batchCounter.incrementAndGet();
    }

    /** Resolve {@code target} under {@code base} and reject any escape (defence in depth). */
    private static Path resolveUnder(Path base, RelativePath target) {
        Path resolved = base.resolve(target.value()).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("path escapes managed root: " + target);
        }
        return resolved;
    }

    private static void atomicMove(Path src, Path dst) {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Different filesystem: fall back to a replacing move (small non-atomic window).
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                throw new UncheckedIOException("move failed: " + src + " -> " + dst, inner);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("move failed: " + src + " -> " + dst, e);
        }
    }

    private static void createParentDirs(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create directory: " + parent, e);
        }
    }
}
