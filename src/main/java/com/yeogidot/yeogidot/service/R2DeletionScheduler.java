package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "r2.deletion.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class R2DeletionScheduler {
    private final R2DeletionWorker worker;

    @Scheduled(fixedDelayString = "${r2.deletion.poll-interval-ms:5000}")
    public void poll() {
        try {
            worker.runOnce();
        } catch (RuntimeException exception) {
            log.error("R2 삭제 작업 조회 실패: error={}", exception.getClass().getSimpleName());
        }
    }
}
