// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.InstalledMod;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.LoaderType;
import java.util.List;

/** Reports loader facts. Populate installedMods/versions from ModList on the build
 *  machine (net.neoforged.fml.ModList.get()). */
public final class NeoForgeLoaderInfo implements LoaderInfo {
    private final String mcVersion;
    private final String skeinVersion;
    private final List<InstalledMod> mods;

    public NeoForgeLoaderInfo(String mcVersion, String skeinVersion, List<InstalledMod> mods) {
        this.mcVersion = mcVersion; this.skeinVersion = skeinVersion; this.mods = List.copyOf(mods);
    }
    @Override public LoaderType loader() { return LoaderType.NEOFORGE; }
    @Override public String minecraftVersion() { return mcVersion; }
    @Override public String skeinVersion() { return skeinVersion; }
    @Override public List<InstalledMod> installedMods() { return mods; }
}
