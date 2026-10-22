package com.pathongko.skein.core.sync;

import java.util.function.Consumer;

/**
 * Publish/subscribe for {@link SyncEvent}s. A port: the production adapter can
 * dispatch asynchronously onto the game thread, while tests use a synchronous,
 * recording implementation.
 */
public interface EventBus {

    void publish(SyncEvent event);

    Subscription subscribe(Consumer<SyncEvent> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
