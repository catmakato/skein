package com.pathongko.skein.core.domain;

/**
 * The kind of file being synced. Drives where a file lands on disk (via the
 * storage layout) and can carry category-wide default policies.
 */
public enum FileCategory {
    MOD,
    CONFIG,
    RESOURCEPACK,
    SHADERPACK,
    DATAPACK,
    OTHER
}
