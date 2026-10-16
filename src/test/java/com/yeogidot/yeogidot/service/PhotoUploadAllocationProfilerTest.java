package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoUploadAllocationProfilerTest {
    @TempDir Path directory;
    private static volatile Object retainedUntilRecorded;

    @Test
    void 기록한_큰_배열과_검증구간을_연결하고_진단범위를_명시한다() throws Exception {
        Path recording = directory.resolve("contract.jfr");
        try (var profiler = PhotoUploadAllocationProfiler.start(recording)) {
            var stage = PhotoUploadAllocationProfiler.begin("validation", 1, 0, "contract-only.jpg");
            try { retainedUntilRecorded = new byte[8 * 1024 * 1024]; }
            finally { PhotoUploadAllocationProfiler.finish(stage); }
        } finally { retainedUntilRecorded = null; }
        var analysis = PhotoUploadAllocationProfiler.analyze(recording);
        assertThat(analysis.get("stageEvents")).isEqualTo(1);
        assertThat((long) analysis.get("dataLossBytes")).isZero();
        assertThat((int) analysis.get("attributedLargeEvents")).isPositive();
        assertThat((long) analysis.get("attributedLargeCumulativeBytes")).isGreaterThanOrEqualTo(8 * 1024 * 1024);
        assertThat(analysis.get("validationIntervalPeak")).isEqualTo(1);
        assertThat(analysis.get("uploadIntervalPeak")).isEqualTo(0);
        assertThat(analysis.get("scope").toString()).contains("NOT live memory");
        assertThat(PhotoUploadAllocationProfiler.enabled()).isFalse();
    }

    @Test
    void 기록을_켜지_않으면_구간_이벤트를_만들지_않는다() {
        assertThat(PhotoUploadAllocationProfiler.enabled()).isFalse();
        assertThat(PhotoUploadAllocationProfiler.begin("validation", 1, 0, "unused.jpg")).isNull();
        PhotoUploadAllocationProfiler.finish(null);
    }

    @Test
    void 배열할당_스택의_분류를_구분한다() {
        assertThat(PhotoUploadAllocationProfiler.classify("upload", List.of("software.amazon.awssdk.core.sync.RequestBody.fromBytes:100")))
                .isEqualTo("sdk-request-body-copy");
        assertThat(PhotoUploadAllocationProfiler.classify("upload", List.of("net.coobird.thumbnailator.util.BufferedImages.copy:67")))
                .isEqualTo("jpeg-output-rgb-copy");
        assertThat(PhotoUploadAllocationProfiler.classify("upload", List.of("java.io.ByteArrayOutputStream.toByteArray:185")))
                .isEqualTo("encoded-result-copy");
        assertThat(PhotoUploadAllocationProfiler.classify("validation", List.of("com.sun.imageio.plugins.jpeg.JPEGImageReader.read:100")))
                .isEqualTo("validation-image-read");
        assertThat(PhotoUploadAllocationProfiler.classify("upload", List.of("unrecognized.method:1")))
                .isEqualTo("other-upload-stage");
    }
}
