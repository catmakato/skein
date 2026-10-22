package com.pathongko.skein.core.error;

import com.pathongko.skein.common.util.Preconditions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The typed exception every Skein service throws. It carries a stable
 * {@link ErrorCode}, a {@link Recoverability}, and structured context (path, url,
 * attempt, ...) kept out of the message string so logging and the UI can consume
 * it. Infrastructure adapters wrap their own exceptions (e.g. a download's
 * TransferException) into this before it crosses back into the core.
 */
public class SkeinException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Recoverability recoverability;
    private final transient Map<String, Object> context = new LinkedHashMap<>();

    public SkeinException(ErrorCode code, Recoverability recoverability, String message) {
        this(code, recoverability, message, null);
    }

    public SkeinException(ErrorCode code, Recoverability recoverability, String message, Throwable cause) {
        super(message, cause);
        this.code = Preconditions.requireNonNull(code, "code");
        this.recoverability = Preconditions.requireNonNull(recoverability, "recoverability");
    }

    public SkeinException with(String key, Object value) {
        context.put(key, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public Recoverability recoverability() {
        return recoverability;
    }

    public Map<String, Object> context() {
        return Map.copyOf(context);
    }
}
