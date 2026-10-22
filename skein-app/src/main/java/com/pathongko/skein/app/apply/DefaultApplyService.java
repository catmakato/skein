package com.pathongko.skein.app.apply;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.diff.PlannedDeletion;
import com.pathongko.skein.core.diff.PlannedFetch;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.error.SkeinException;
import com.pathongko.skein.core.port.CommitReceipt;
import com.pathongko.skein.core.port.FileManager;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.storage.file.DefaultFileManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements {@link ApplyService} over the {@link FileManager}: atomically commit
 * every staged download, then quarantine the plan's deletions. If any step fails
 * part-way, the commits already made are rolled back so the instance is never left
 * in a half-applied state.
 */
public final class DefaultApplyService implements ApplyService {

    private final FileManager fileManager;

    public DefaultApplyService(FileManager fileManager) {
        this.fileManager = Preconditions.requireNonNull(fileManager, "fileManager");
    }

    @Override
    public void apply(SyncPlan plan) {
        List<CommitReceipt> committed = new ArrayList<>();
        DefaultFileManager batchManager = fileManager instanceof DefaultFileManager concrete ? concrete : null;
        if (batchManager != null) batchManager.beginBackupBatch();
        try {
            for (PlannedFetch fetch : plan.fetches()) {
                committed.add(fileManager.commit(fetch.entry().path()));
            }
            for (PlannedDeletion deletion : plan.deletions()) {
                if (deletion.policy() != DeletionPolicy.KEEP) {
                    fileManager.quarantine(deletion.path());
                }
            }
        } catch (RuntimeException e) {
            rollback(committed);
            throw new SkeinException(ErrorCode.APPLY_FAILED, Recoverability.FATAL,
                    "apply failed and was rolled back", e);
        } finally {
            if (batchManager != null) batchManager.endBackupBatch();
        }
    }

    private void rollback(List<CommitReceipt> committed) {
        // Undo in reverse order; a rollback failure must not mask the original cause.
        for (int i = committed.size() - 1; i >= 0; i--) {
            try {
                fileManager.rollback(committed.get(i));
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }
}
