package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RememberedEndpointCacheTest {

    @Test
    void emptyWhenNeverWritten(@TempDir Path dir) {
        assertThat(new RememberedEndpointCache(dir.resolve("skein")).get("play.example.com:25565"))
                .isEmpty();
    }

    @Test
    void roundTrips(@TempDir Path dir) {
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));

        cache.put("play.example.com:25565", "http://play.example.com:25585");

        assertThat(cache.get("play.example.com:25565"))
                .contains("http://play.example.com:25585");
    }

    @Test
    void unknownAddressIsEmpty(@TempDir Path dir) {
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));

        cache.put("play.example.com:25565", "http://play.example.com:25585");

        assertThat(cache.get("other.example.com:25565")).isEmpty();
    }

    @Test
    void puttingANewAddressPreservesExistingOnes(@TempDir Path dir) {
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));
        cache.put("a.example.com:25565", "http://a.example.com:25585");

        cache.put("b.example.com:25565", "http://b.example.com:25585");

        assertThat(cache.get("a.example.com:25565")).contains("http://a.example.com:25585");
        assertThat(cache.get("b.example.com:25565")).contains("http://b.example.com:25585");
    }

    @Test
    void puttingTheSameAddressAgainOverwritesTheOldValue(@TempDir Path dir) {
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));
        cache.put("play.example.com:25565", "http://old-host:25585");

        cache.put("play.example.com:25565", "http://new-host:25585");

        assertThat(cache.get("play.example.com:25565")).contains("http://new-host:25585");
    }

    @Test
    void unversionedFileIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.writeString(skein.resolve("remembered-endpoints.txt"),
                "play.example.com:25565=http://play.example.com:25585\n");

        assertThat(new RememberedEndpointCache(skein).get("play.example.com:25565")).isEmpty();
    }

    @Test
    void malformedContentIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.write(skein.resolve("remembered-endpoints.txt"), new byte[]{(byte) 0xFF, 0, 1, 2});

        assertThatCode(() -> new RememberedEndpointCache(skein).get("x")).doesNotThrowAnyException();
        assertThat(new RememberedEndpointCache(skein).get("x")).isEmpty();
    }

    @Test
    void putWithAnEmbeddedNewlineInTheUrlDoesNotCorruptTheFile(@TempDir Path dir) {
        // A URL containing "\n" would, if written naively, inject an extra line into
        // this one-entry-per-line file. The whole write must be aborted instead.
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));

        cache.put("play.example.com:25565", "http://evil\nplay.example.com:25585");

        assertThat(cache.get("play.example.com:25565")).isEmpty();
    }

    @Test
    void putWithAnEmbeddedNewlineDoesNotClobberAPreviouslyWrittenEntry(@TempDir Path dir) {
        RememberedEndpointCache cache = new RememberedEndpointCache(dir.resolve("skein"));
        cache.put("play.example.com:25565", "http://play.example.com:25585");

        cache.put("other.example.com:25565", "http://evil\ninjected:25585");

        assertThat(cache.get("play.example.com:25565")).contains("http://play.example.com:25585");
        assertThat(cache.get("other.example.com:25565")).isEmpty();
    }
}
