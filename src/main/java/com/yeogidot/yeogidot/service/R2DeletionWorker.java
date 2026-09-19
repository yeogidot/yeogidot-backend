package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class R2DeletionWorker {
    private final R2DeletionTaskService tasks;
    private final GcsService storage;

    public void runOnce() {
        for (Long id : tasks.readyIds()) {
            try {
                process(id);
            } catch (RuntimeException exception) {
                // DB 결과 기록 실패도 작업을 없애지 않는다. PROCESSING은 만료 후 다시 선점된다.
                log.error("R2 삭제 작업 처리 중 오류: taskId={}, error={}", id,
                        exception.getClass().getSimpleName());
            }
        }
    }

    public void process(Long id) {
        var claimed = tasks.claim(id);
        if (claimed.isEmpty()) return;
        var claim = claimed.get();
        try {
            storage.deleteFileStrict(claim.fileUrl());
        } catch (RuntimeException exception) {
            tasks.failed(claim, exception);
            return;
        }
        // 여기서 DB 기록이 실패하거나 프로세스가 종료되면 파일 삭제를 다시 수행할 수 있다.
        tasks.complete(claim);
    }
}
