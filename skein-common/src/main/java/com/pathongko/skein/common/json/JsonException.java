package com.pathongko.skein.common.json;

/** Thrown when JSON is malformed. */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
