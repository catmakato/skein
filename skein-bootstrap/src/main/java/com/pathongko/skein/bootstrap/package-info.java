/**
 * Loader-agnostic composition root and session state machines. Wires the shared
 * sync engine together and drives the client/server handshake. Contains NO
 * {@code net.minecraft.*} — each loader adapter hands it a {@code ClientPlatform}
 * / {@code ControlChannel} and this module does the rest.
 */
package com.pathongko.skein.bootstrap;
