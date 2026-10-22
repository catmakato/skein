package com.pathongko.skein.common.log;

/**
 * Logging facade. Core code logs through this interface only, so that no module
 * except the platform adapter depends on a concrete logging framework
 * (SLF4J/Log4j/Minecraft). Messages are structured: an {@code event} name plus
 * alternating key/value pairs, never pre-formatted strings.
 *
 * <pre>{@code
 * log.info("file.downloaded", "path", entry.path(), "bytes", size, "ms", elapsed);
 * }</pre>
 */
public interface SkeinLogger {

    void debug(String event, Object... keyValues);

    void info(String event, Object... keyValues);

    void warn(String event, Throwable error, Object... keyValues);

    void error(String event, Throwable error, Object... keyValues);

    boolean isDebugEnabled();
}
