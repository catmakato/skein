// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.PlatformPaths;
import java.nio.file.Path;

/** Resolves game directories from FML. On the build machine, confirm the FMLPaths
 *  accessors (gameDir(), configDir()) against NeoForge 47.x. */
public final class NeoForgePlatformPaths implements PlatformPaths {
    private final Path gameDir;
    public NeoForgePlatformPaths(Path gameDir) { this.gameDir = gameDir; }

    @Override public Path gameDir() { return gameDir; }
    @Override public Path configDir() { return gameDir.resolve("config"); }
    @Override public Path modsDir() { return gameDir.resolve("mods"); }
    @Override public Path skeinDir() { return gameDir.resolve(".skein"); }
}
