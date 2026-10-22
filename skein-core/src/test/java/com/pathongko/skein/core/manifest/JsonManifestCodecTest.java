package com.pathongko.skein.core.manifest;

import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JsonManifestCodecTest {

    private final JsonManifestCodec codec = new JsonManifestCodec();

    private static FileHash hash(char c) {
        return FileHash.sha256(String.valueOf(c).repeat(64));
    }

    @Test
    void rootManifestRoundTrips() {
        RootManifest root = new RootManifest(1, "pack", PackVersion.of("2026.07.05-3"),
                Instant.parse("2026-07-05T10:00:00Z"), HashAlgorithm.SHA256,
                List.of(new IndexRef(FileCategory.MOD, "index/mods.json", hash('a'), 3)),
                Optional.of("sig=="));

        RootManifest decoded = codec.decodeRoot(codec.encodeRoot(root));

        assertThat(decoded).isEqualTo(root);
    }

    @Test
    void categoryIndexRoundTrips() {
        FileEntry entry = new FileEntry(new RelativePath("mods/a.jar"), 123L, hash('a'),
                FileCategory.MOD, SyncPolicy.mandatory(),
                Set.of(new DownloadSource(URI.create("http://h/files/x"), 0)));
        CategoryIndex index = new CategoryIndex(FileCategory.MOD, List.of(entry));

        CategoryIndex decoded = codec.decodeIndex(codec.encodeIndex(index));

        assertThat(decoded).isEqualTo(index);
    }

    @Test
    void encodingIsStableAcrossCalls() {
        RootManifest root = new RootManifest(1, "pack", PackVersion.of("1.0.0"),
                Instant.EPOCH, HashAlgorithm.SHA256, List.of(), Optional.empty());
        assertThat(codec.encodeRoot(root)).isEqualTo(codec.encodeRoot(root));
    }
}
