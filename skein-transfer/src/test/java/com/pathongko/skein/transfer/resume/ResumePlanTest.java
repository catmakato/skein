package com.pathongko.skein.transfer.resume;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResumePlanTest {

    @Test
    void noPartialMeansFresh() {
        ResumePlan p = ResumePlan.compute(0, 8_000, true);
        assertThat(p.action()).isEqualTo(ResumePlan.Action.FRESH);
        assertThat(p.startOffset()).isZero();
    }

    @Test
    void validPartialResumesFromOffset() {
        ResumePlan p = ResumePlan.compute(4_200, 8_000, true);
        assertThat(p.action()).isEqualTo(ResumePlan.Action.RESUME);
        assertThat(p.startOffset()).isEqualTo(4_200);
    }

    @Test
    void completePartialSkipsDownload() {
        assertThat(ResumePlan.compute(8_000, 8_000, true).action()).isEqualTo(ResumePlan.Action.COMPLETE);
    }

    @Test
    void oversizedPartialRestarts() {
        assertThat(ResumePlan.compute(9_999, 8_000, true).action()).isEqualTo(ResumePlan.Action.RESTART);
    }

    @Test
    void serverWithoutRangeSupportRestarts() {
        ResumePlan p = ResumePlan.compute(4_200, 8_000, false);
        assertThat(p.action()).isEqualTo(ResumePlan.Action.RESTART);
        assertThat(p.startOffset()).isZero();
    }
}
