// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.platform;

import com.pathongko.skein.platform.InstalledMod;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.LoaderType;
import java.util.List;

/** Reports loader facts. {@code mods} is populated by {@link
 *  com.pathongko.skein.forge.client.SkeinClient} from {@code ModList.get()}. */
public final class ForgeLoaderInfo implements LoaderInfo {
    private final String mcVersion;
    private final String skeinVersion;
    private final List<InstalledMod> mods;

    public ForgeLoaderInfo(String mcVersion, String skeinVersion, List<InstalledMod> mods) {
        this.mcVersion = mcVersion; this.skeinVersion = skeinVersion; this.mods = List.copyOf(mods);
    }
    @Override public LoaderType loader() { return LoaderType.FORGE; }
    @Override public String minecraftVersion() { return mcVersion; }
    @Override public String skeinVersion() { return skeinVersion; }
    @Override public List<InstalledMod> installedMods() { return mods; }
}
