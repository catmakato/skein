package com.pathongko.skein.platform;

import com.pathongko.skein.common.util.Preconditions;

/**
 * A mod the loader reports as installed locally. Used to build the client's
 * current view before diffing against the server manifest.
 */
public record InstalledMod(String modId, String version) {

    public InstalledMod {
        Preconditions.requireNonBlank(modId, "modId");
        Preconditions.requireNonBlank(version, "version");
    }
}
