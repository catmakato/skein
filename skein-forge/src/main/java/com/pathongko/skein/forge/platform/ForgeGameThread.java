// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.platform;

import com.pathongko.skein.platform.GameThreadExecutor;
import net.minecraft.client.Minecraft;

/** Posts work onto the client main thread. */
public final class ForgeGameThread implements GameThreadExecutor {
    @Override public void execute(Runnable command) {
        Minecraft.getInstance().execute(command);
    }
}
