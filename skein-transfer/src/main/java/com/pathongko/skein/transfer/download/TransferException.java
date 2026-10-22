package com.pathongko.skein.transfer.download;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.transfer.retry.FailureKind;

/**
 * A download failure carrying its classified {@link FailureKind}, so the retry
 * policy can decide what to do without re-inspecting the cause.
 */
public final class TransferException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient FailureKind kind;

    public TransferException(FailureKind kind, String message) {
        super(message);
        this.kind = Preconditions.requireNonNull(kind, "kind");
    }

    public TransferException(FailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Preconditions.requireNonNull(kind, "kind");
    }

    public FailureKind kind() {
        return kind;
    }
}
