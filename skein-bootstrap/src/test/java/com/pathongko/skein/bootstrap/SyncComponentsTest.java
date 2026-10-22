package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncComponentsTest {
    @Test
    void buildsAnOrchestratorWithoutTouchingTheNetwork(@org.junit.jupiter.api.io.TempDir Path dir) {
        var orch = SyncComponents.forSync(
                "http://127.0.0.1:1", dir, Optional.empty(), new SynchronousEventBus(), Set.of());
        assertThat(orch).isNotNull();
        assertThat(orch.currentState().name()).isEqualTo("IDLE");
    }

    @Test
    void categoryDirsAreTheCanonicalMap() {
        assertThat(SyncComponents.CATEGORY_DIRS)
                .containsEntry(FileCategory.MOD, "mods")
                .containsEntry(FileCategory.CONFIG, "config")
                .containsEntry(FileCategory.RESOURCEPACK, "resourcepacks")
                .containsEntry(FileCategory.SHADERPACK, "shaderpacks")
                .containsEntry(FileCategory.DATAPACK, "datapacks");
    }
}
