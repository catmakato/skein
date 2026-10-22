package com.pathongko.skein.server;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.server.manage.ManagedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedPackScaffoldTest {

    private static Map<FileCategory, String> categoryDirs() {
        Map<FileCategory, String> dirs = new EnumMap<>(FileCategory.class);
        dirs.put(FileCategory.MOD, "mods");
        dirs.put(FileCategory.CONFIG, "config");
        dirs.put(FileCategory.RESOURCEPACK, "resourcepacks");
        dirs.put(FileCategory.SHADERPACK, "shaderpacks");
        dirs.put(FileCategory.DATAPACK, "datapacks");
        return dirs;
    }

    private static ManagedPack managedPackAt(Path packDir) {
        return new ManagedPack(packDir, "skein-pack", PackVersion.of("1.0.0"), HashAlgorithm.SHA256,
                "http://localhost:25585/files/", categoryDirs(),
                new MessageDigestHashService(), new JsonManifestCodec());
    }

    @Test
    void createsPackDirAndEveryCategorySubdirWhenMissing(@TempDir Path parent) {
        // packDir itself does not exist yet — mimics an operator pointing packDir at a
        // fresh path in skein-server.properties.
        Path packDir = parent.resolve("skein-pack");
        assertThat(Files.exists(packDir)).isFalse();

        managedPackAt(packDir);

        assertThat(Files.isDirectory(packDir)).as("packDir").isTrue();
        for (String name : categoryDirs().values()) {
            assertThat(Files.isDirectory(packDir.resolve(name))).as(name).isTrue();
        }
    }

    @Test
    void leavesExistingFilesUntouchedWhileFillingInMissingDirs(@TempDir Path packDir) throws Exception {
        // A pack that already has mods/ with a file, but is missing the other dirs.
        Files.createDirectories(packDir.resolve("mods"));
        Files.writeString(packDir.resolve("mods/existing.jar"), "keep me");

        managedPackAt(packDir);

        assertThat(Files.readString(packDir.resolve("mods/existing.jar"))).isEqualTo("keep me");
        assertThat(Files.isDirectory(packDir.resolve("config"))).isTrue();
        assertThat(Files.isDirectory(packDir.resolve("datapacks"))).isTrue();
    }
}
