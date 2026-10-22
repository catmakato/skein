package com.pathongko.skein.platform;

import java.nio.file.Path;

/**
 * Filesystem locations resolved by the loader (they differ slightly between
 * client and dedicated server, and between loaders). Shared code never guesses
 * these paths.
 */
public interface PlatformPaths {

    /** The instance root (the ".minecraft" directory, or the server directory). */
    Path gameDir();

    Path configDir();

    Path modsDir();

    /** Skein's private working area for cache, staging and quarantine. */
    Path skeinDir();
}
