// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.GameThreadExecutor;
import net.minecraft.client.Minecraft;

/** Posts work onto the client main thread. */
public final class NeoForgeGameThread implements GameThreadExecutor {
    @Override public void execute(Runnable command) {
        Minecraft.getInstance().execute(command);
    }
}
