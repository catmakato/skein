package com.pathongko.skein.core.sync;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A simple event bus that delivers to each subscriber immediately on the calling
 * thread. One subscriber throwing does not stop the others (isolation). The
 * NeoForge/Forge adapters can supply an async, game-thread-marshalling bus later
 * without touching the core.
 */
public final class SynchronousEventBus implements EventBus {

    private final CopyOnWriteArrayList<Consumer<SyncEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(SyncEvent event) {
        for (Consumer<SyncEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException isolated) {
                // A misbehaving subscriber must not break the sync or other subscribers.
            }
        }
    }

    @Override
    public Subscription subscribe(Consumer<SyncEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
