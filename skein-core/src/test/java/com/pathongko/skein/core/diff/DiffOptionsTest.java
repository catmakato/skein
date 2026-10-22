package com.pathongko.skein.core.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.RelativePath;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffOptionsTest {

    @Test
    void defaultsHasNoLedger() {
        DiffOptions options = DiffOptions.defaults();
        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.QUARANTINE);
        assertThat(options.previouslyManaged()).isEmpty();
    }

    @Test
    void additiveOnlyHasNoLedger() {
        DiffOptions options = DiffOptions.additiveOnly();
        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.KEEP);
        assertThat(options.previouslyManaged()).isEmpty();
    }

    @Test
    void safeCarriesTheGivenLedger() {
        RelativePath tracked = new RelativePath("mods/a.jar");
        DiffOptions options = DiffOptions.safe(Set.of(tracked));

        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.QUARANTINE);
        assertThat(options.previouslyManaged()).contains(Set.of(tracked));
    }

    @Test
    void safeManagesTheStandardCategoryDirs() {
        DiffOptions options = DiffOptions.safe(Set.of());
        assertThat(options.manages("mods")).isTrue();
        assertThat(options.manages("config")).isTrue();
        assertThat(options.manages("saves")).isFalse();
    }

    @Test
    void safeRejectsANullLedgerWithANamedException() {
        assertThatNullPointerException()
                .isThrownBy(() -> DiffOptions.safe(null))
                .withMessageContaining("previouslyManaged");
    }
}
