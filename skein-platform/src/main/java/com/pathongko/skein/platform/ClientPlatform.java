package com.pathongko.skein.platform;

import com.pathongko.skein.platform.net.ControlChannel;

/**
 * Everything the client-side sync engine needs from the surrounding loader,
 * gathered behind one interface. Each loader adapter builds one concrete
 * {@code ClientPlatform} and hands it to the shared bootstrap, which wires up the
 * (loader-agnostic) sync engine. This is the single seam that makes Forge and
 * NeoForge share ~90% of the code.
 */
public interface ClientPlatform {

    LoaderInfo loaderInfo();

    PlatformPaths paths();

    GameThreadExecutor gameThread();

    ControlChannel controlChannel();

    ClientRelauncher relauncher();
}
