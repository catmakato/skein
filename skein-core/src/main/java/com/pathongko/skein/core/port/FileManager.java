package com.pathongko.skein.core.port;

import com.pathongko.skein.core.domain.RelativePath;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Safely turns downloaded bytes into files in the live instance. The invariant is
 * that the real file tree is only ever changed by an atomic move — there is no
 * moment where a target file is half-written — and replaced/extra files are moved
 * to quarantine rather than hard-deleted, so any step can be undone.
 */
public interface FileManager {

    /** The staging path a downloader should write {@code target} to (parent dirs created). */
    Path stagingFileFor(RelativePath target);

    /** Atomically move the staged file into place, quarantining any existing file. */
    CommitReceipt commit(RelativePath target);

    /** Move an existing live file out to quarantine (used for extras marked for deletion). */
    Optional<Path> quarantine(RelativePath target);

    /** Undo a commit: remove the committed file and restore the quarantined previous one. */
    void rollback(CommitReceipt receipt);
}
