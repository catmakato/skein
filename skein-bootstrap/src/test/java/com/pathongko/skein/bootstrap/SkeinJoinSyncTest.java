package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.bootstrap.support.LocalPackServer;
import com.pathongko.skein.core.sync.SyncOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkeinJoinSyncTest {

    @Test
    void discoverFindsALiveServer(@TempDir Path root) throws Exception {
        try (LocalPackServer server = new LocalPackServer(root.resolve("pack"))) {
            FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
            SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(5)));

            // Probe the ephemeral URL directly (discoverAt), so the test doesn't
            // depend on the conventional port 25585 being free on the CI box.
            Optional<String> found = join.discoverAt(server.endpoint(), Optional.empty());
            assertThat(found).contains(server.endpoint());
        }
    }

    @Test
    void discoverReturnsEmptyWhenNothingAnswers(@TempDir Path root) {
        FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
        SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(2)));
        assertThat(join.discoverAt("http://127.0.0.1:1", Optional.empty())).isEmpty();
    }

    @Test
    void syncFromDownloadsThePack(@TempDir Path root) throws Exception {
        try (LocalPackServer server = new LocalPackServer(root.resolve("pack"))) {
            Path gameDir = root.resolve("client");
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(5)));

            SyncOutcome outcome = join.syncFrom(server.endpoint(), Optional.empty(), e -> {});

            assertThat(outcome).isInstanceOf(SyncOutcome.RestartRequired.class);
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isTrue();
        }
    }
}
