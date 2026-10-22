package com.pathongko.skein.platform.net;

/**
 * An opaque handle to a connected player, owned by the loader adapter. Shared
 * code passes it back to the adapter (e.g. to send a packet to that player)
 * without knowing the underlying Minecraft type.
 */
public interface PlayerRef {

    /** Stable id for logging/correlation (e.g. the player's UUID as a string). */
    String id();
}
