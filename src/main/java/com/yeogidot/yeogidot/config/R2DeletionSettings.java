package com.yeogidot.yeogidot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "r2.deletion")
public record R2DeletionSettings(
        @DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("5") @Min(1) long initialRetrySeconds,
        @DefaultValue("300") @Min(1) long maxRetrySeconds,
        // R2 삭제 요청 전체 제한 30초보다 길게 유지한다.
        @DefaultValue("120") @Min(60) long leaseSeconds,
        @DefaultValue("20") @Min(1) @Max(100) int batchSize
) {
    public R2DeletionSettings {
        if (maxAttempts < 1 || maxAttempts > 20 || initialRetrySeconds < 1
                || maxRetrySeconds < initialRetrySeconds || leaseSeconds < 60
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("R2 삭제 재시도 설정이 올바르지 않습니다.");
        }
    }

    public long retryDelaySeconds(int attemptCount) {
        long delay = initialRetrySeconds;
        for (int i = 1; i < attemptCount && delay < maxRetrySeconds; i++) {
            delay = delay > maxRetrySeconds / 2 ? maxRetrySeconds : delay * 2;
        }
        return Math.min(delay, maxRetrySeconds);
    }
}
