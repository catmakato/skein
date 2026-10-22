// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.platform;

import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.ClientRelauncher;
import com.pathongko.skein.platform.GameThreadExecutor;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.ControlChannel;

public final class ForgeClientPlatform implements ClientPlatform {
    private final LoaderInfo loaderInfo;
    private final PlatformPaths paths;
    private final GameThreadExecutor gameThread;
    private final ControlChannel controlChannel;
    private final ClientRelauncher relauncher;

    public ForgeClientPlatform(LoaderInfo loaderInfo, PlatformPaths paths,
            GameThreadExecutor gameThread, ControlChannel controlChannel, ClientRelauncher relauncher) {
        this.loaderInfo = loaderInfo; this.paths = paths; this.gameThread = gameThread;
        this.controlChannel = controlChannel; this.relauncher = relauncher;
    }
    @Override public LoaderInfo loaderInfo() { return loaderInfo; }
    @Override public PlatformPaths paths() { return paths; }
    @Override public GameThreadExecutor gameThread() { return gameThread; }
    @Override public ControlChannel controlChannel() { return controlChannel; }
    @Override public ClientRelauncher relauncher() { return relauncher; }
}
