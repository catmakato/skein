package com.pathongko.skein.platform;

/**
 * Restarts the client after new/updated mods have been staged (mods can only be
 * loaded at JVM start — see design doc, Part 3). Relaunching is best-effort and
 * depends on how the game was launched; when it is not possible the UI falls back
 * to asking the player to restart manually.
 */
public interface ClientRelauncher {

    boolean canRelaunch();

    /** Attempt to relaunch. Only called when {@link #canRelaunch()} is true. */
    void relaunch();
}
