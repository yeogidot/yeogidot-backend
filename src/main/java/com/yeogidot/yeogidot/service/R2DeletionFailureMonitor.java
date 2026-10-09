package com.yeogidot.yeogidot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 경고 로그 기반 감지. 외부 메시지 전송이나 여러 서버 사이의 알림 중복 방지는 범위 밖이다. */
@Slf4j
@Component
@ConditionalOnProperty(name = {"r2.deletion.monitoring-enabled", "r2.deletion.scheduling-enabled"},
        havingValue = "true", matchIfMissing = true)
public class R2DeletionFailureMonitor {
    private final R2DeletionOperationsService operations;
    private R2DeletionOperationsService.FailureMarker lastFailure;
    private long lastFailedCount;
    private boolean queryFailed;

    public R2DeletionFailureMonitor(R2DeletionOperationsService operations) { this.operations = operations; }

    @Scheduled(fixedDelayString = "${r2.deletion.monitor-interval-ms:60000}")
    public synchronized void poll() {
        try {
            var summary = operations.summary();
            if (queryFailed) log.info("R2 삭제 작업 모니터 조회 복구");
            queryFailed = false;
            if (summary.failed() > 0 && (lastFailedCount != summary.failed()
                    || !Objects.equals(lastFailure, summary.latestFailure()))) {
                log.warn("R2 삭제 실패 작업 확인 필요: failed={}, pending={}, processing={}, latestTaskId={}",
                        summary.failed(), summary.pending(), summary.processing(),
                        summary.latestFailure() == null ? null : summary.latestFailure().taskId());
            } else if (summary.failed() == 0 && lastFailedCount > 0) {
                log.info("R2 FAILED 작업 0건: 재처리 예약이 실제 파일 삭제 완료를 뜻하지는 않습니다.");
            }
            lastFailedCount = summary.failed();
            lastFailure = summary.latestFailure();
        } catch (RuntimeException failure) {
            if (!queryFailed) log.error("R2 삭제 작업 모니터 조회 실패: error={}", failure.getClass().getSimpleName());
            queryFailed = true;
        }
    }
}
