package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.config.R2DeletionSettings;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class R2DeletionTaskService {
    private final R2DeletionTaskRepository repository;
    private final Clock clock;
    private final R2DeletionSettings settings;

    public R2DeletionTaskService(R2DeletionTaskRepository repository,
                                 @Qualifier("r2DeletionClock") Clock clock,
                                 R2DeletionSettings settings) {
        this.repository = repository;
        this.clock = clock;
        this.settings = settings;
    }

    /** 반드시 사진 삭제와 같은 트랜잭션에 저장한다. 독립 커밋은 허용하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueue(String fileUrl) {
        return repository.save(R2DeletionTask.pending(fileUrl, clock.instant())).getId();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> readyIds() {
        return repository.findReadyIds(List.of(R2DeletionTask.Status.PENDING,
                        R2DeletionTask.Status.PROCESSING), clock.instant(),
                PageRequest.of(0, settings.batchSize()));
    }

    /** 짧은 DB 락으로 선점한 뒤 커밋한다. 외부 통신 중에는 DB 락을 보유하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(Long id) {
        Instant now = clock.instant();
        R2DeletionTask task = repository.findLockedById(id).orElse(null);
        if (task == null || !task.isReady(now)) {
            return Optional.empty();
        }
        if (task.getAttemptCount() >= settings.maxAttempts()) {
            task.fail(now, "LeaseExpiredAfterMaxAttempts");
            log.error("R2 삭제 작업 수동 확인 필요: taskId={}, attempts={}", id, task.getAttemptCount());
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        task.claim(token, now, now.plusSeconds(settings.leaseSeconds()));
        return Optional.of(new Claim(id, task.getFileUrl(), token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Claim claim) {
        repository.findLockedById(claim.id()).filter(t -> t.isOwnedBy(claim.token()))
                .ifPresent(t -> t.complete(clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Claim claim, RuntimeException failure) {
        repository.findLockedById(claim.id()).filter(t -> t.isOwnedBy(claim.token())).ifPresent(task -> {
            Instant now = clock.instant();
            // SDK 오류 메시지에는 URL/요청 정보가 섞일 수 있어 예외 종류만 저장한다.
            String error = failure.getClass().getSimpleName();
            if (error.length() > 255) error = error.substring(0, 255);
            if (failure instanceof IllegalArgumentException || task.getAttemptCount() >= settings.maxAttempts()) {
                task.fail(now, error);
                log.error("R2 삭제 작업 수동 확인 필요: taskId={}, attempts={}, error={}",
                        task.getId(), task.getAttemptCount(), error);
            } else {
                task.retry(now, now.plusSeconds(settings.retryDelaySeconds(task.getAttemptCount())), error);
                log.warn("R2 삭제 재시도 예약: taskId={}, attempts={}, nextAttempt={}",
                        task.getId(), task.getAttemptCount(), task.getAvailableAt());
            }
        });
    }

    public record Claim(Long id, String fileUrl, String token) { }
}
