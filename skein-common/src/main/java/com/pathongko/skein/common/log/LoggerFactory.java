package com.pathongko.skein.common.log;

/**
 * Produces {@link SkeinLogger} instances. Injected into services so tests can
 * supply a recording logger and the NeoForge adapter can supply an SLF4J-backed
 * one.
 */
public interface LoggerFactory {

    SkeinLogger get(Class<?> owner);
}
