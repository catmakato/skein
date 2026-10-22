package com.pathongko.skein.bootstrap;

import com.pathongko.skein.platform.net.PlayerRef;

/** A {@link PlayerRef} test double with a caller-given id. */
final class NamedPlayer implements PlayerRef {

    private final String id;

    NamedPlayer(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}
