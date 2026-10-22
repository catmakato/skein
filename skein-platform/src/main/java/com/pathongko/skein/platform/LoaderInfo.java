package com.pathongko.skein.platform;

import java.util.List;

/**
 * Read-only facts about the running environment, provided by the loader adapter.
 * Shared code branches on these instead of calling loader APIs directly.
 */
public interface LoaderInfo {

    LoaderType loader();

    /** e.g. "1.20.1". */
    String minecraftVersion();

    /** Version of the Skein mod itself, for the handshake compatibility check. */
    String skeinVersion();

    List<InstalledMod> installedMods();
}
