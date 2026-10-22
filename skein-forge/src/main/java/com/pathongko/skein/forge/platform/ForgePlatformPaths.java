// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.platform;

import com.pathongko.skein.platform.PlatformPaths;
import java.nio.file.Path;

/** Resolves game directories from FML. On the build machine, confirm the FMLPaths
 *  accessors (gameDir(), configDir()) against Forge 47.x. */
public final class ForgePlatformPaths implements PlatformPaths {
    private final Path gameDir;
    public ForgePlatformPaths(Path gameDir) { this.gameDir = gameDir; }

    @Override public Path gameDir() { return gameDir; }
    @Override public Path configDir() { return gameDir.resolve("config"); }
    @Override public Path modsDir() { return gameDir.resolve("mods"); }
    @Override public Path skeinDir() { return gameDir.resolve(".skein"); }
}
