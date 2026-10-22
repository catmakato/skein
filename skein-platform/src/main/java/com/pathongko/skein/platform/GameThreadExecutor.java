package com.pathongko.skein.platform;

import java.util.concurrent.Executor;

/**
 * Posts work onto the Minecraft main thread. The sync engine runs off-thread and
 * uses this only for the few actions that must happen on the game thread (sending
 * control packets, updating the progress screen). Marker interface over
 * {@link Executor} so the intent is explicit at call sites.
 */
public interface GameThreadExecutor extends Executor {
}
