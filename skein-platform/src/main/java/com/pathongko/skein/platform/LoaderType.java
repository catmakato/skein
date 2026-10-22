package com.pathongko.skein.platform;

/**
 * Which mod loader the adapter is running under. Kept in the shared layer so
 * loader-specific behaviour (e.g. how mods are discovered) can be branched on
 * without the shared code depending on any loader API.
 */
public enum LoaderType {
    FORGE,
    NEOFORGE,
    FABRIC; // reserved for a future adapter

    public String displayName() {
        return switch (this) {
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
            case FABRIC -> "Fabric";
        };
    }
}
