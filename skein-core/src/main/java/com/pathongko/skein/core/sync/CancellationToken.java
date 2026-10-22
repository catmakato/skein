package com.pathongko.skein.core.sync;

/**
 * Lets a long sync be cancelled cooperatively. The orchestrator checks it at each
 * phase boundary; 20 GB downloads can take a while, so the player must be able to
 * walk away (design doc, Part 6).
 */
@FunctionalInterface
public interface CancellationToken {

    boolean isCancelled();

    static CancellationToken none() {
        return () -> false;
    }
}
