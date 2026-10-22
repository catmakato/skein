package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftWhitelistTest {

    @Test
    void containsReturnsTrueForAListedUuid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isTrue();
    }

    @Test
    void containsReturnsFalseForAnUnlistedUuid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("00000000-0000-0000-0000-000000000000")).isFalse();
    }

    @Test
    void comparisonIsCaseInsensitive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"C1C19F0B-1234-4A5B-8C9D-0E1F2A3B4C5D","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isTrue();
    }

    @Test
    void emptyWhitelistContainsNobody(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, "[]");

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isFalse();
    }

    @Test
    void missingFileFailsClosed(@TempDir Path dir) {
        Path file = dir.resolve("does-not-exist.json");

        assertThatCode(() -> new MinecraftWhitelist(file).contains("x")).doesNotThrowAnyException();
        assertThat(new MinecraftWhitelist(file).contains("x")).isFalse();
    }

    @Test
    void malformedJsonFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, "{not valid json");

        assertThatCode(() -> new MinecraftWhitelist(file).contains("x")).doesNotThrowAnyException();
        assertThat(new MinecraftWhitelist(file).contains("x")).isFalse();
    }
}
