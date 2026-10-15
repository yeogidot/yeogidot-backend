package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 로컬 MySQL/전용 R2/패키지 CLI를 연결한다. 운영 설정/스케줄러는 로드하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_MANUAL_LIVE_TEST", matches = "true")
@SpringJUnitConfig(R2DeletionManualRecoveryLiveTestConfiguration.class)
@TestPropertySource(properties = {"r2.mysql.suffix=photo", "r2.bucket=yeogidot-r2-delete-test",
        "r2.public-url=https://r2-test.invalid"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2DeletionManualRecoveryLiveIntegrationTest {
    @Autowired private R2LiveTestStorage storage;
    @Autowired private R2DeletionLiveTestConfiguration.FaultInjectingGcsService gcs;
    @Autowired private PhotoService photos;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private R2DeletionTaskRepository tasks;
    @Autowired private R2DeletionWorker worker;
    @Autowired private R2DeletionTaskService taskService;
    @Autowired private R2DeletionOperationsService operations;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource source;
    @PersistenceContext private EntityManager entityManager;
    @TempDir Path directory;
    private R2DeletionManualRecoveryProcess.Target target;

    @BeforeEach void prepare() throws Exception {
        R2DeletionMySqlTestConfiguration.verify(source);
        R2DeletionManualRecoveryProcess.testJar();
        target = R2DeletionManualRecoveryProcess.target(System.getenv());
        assertThat(tasks.count()).isZero();
        clock.reset();
        gcs.failNext.set(false);
    }

    @AfterEach void cleanup() {
        gcs.failNext.set(false);
        storage.cleanup(); // 검증이 끝난 뒤에만 정리한다. 정리 과정의 삭제로 테스트를 통과시키지 않는다.
    }

    @Test void 한도초과_작업을_실제_콘솔로_재처리하고_R2파일과_이력을_확인한다() throws Exception {
        String key = storage.uploadFixture();
        assertThat(storage.exists(key)).isTrue();
        Photo photo = new TransactionTemplate(transactions).execute(status -> {
            User user = User.create(UUID.randomUUID() + "@example.invalid", "test-only");
            entityManager.persist(user);
            Photo created = Photo.builder().user(user).filePath(R2LiveTestStorage.URL_BASE + "/" + key)
                    .originalName("manual-test.txt").takenAt(LocalDateTime.of(2026, 10, 15, 12, 0)).build();
            entityManager.persist(created);
            return created;
        });
        int deletesBefore = storage.deleteInvocations();
        photos.deletePhoto(photo.getId(), photo.getUser().getId());
        assertThat(photoRepository.existsById(photo.getId())).isFalse();
        Long taskId = tasks.findAll().getFirst().getId();

        // 한도는 이 테스트에서 3회. 매 시도 HTTP 요청 전에 1회 예외를 주입한다.
        for (int attempt = 1; attempt <= 3; attempt++) {
            gcs.failNext.set(true);
            worker.runOnce();
            assertThat(tasks.findById(taskId).orElseThrow().getAttemptCount()).isEqualTo(attempt);
            if (attempt < 3) clock.advance(attempt);
        }
        R2DeletionTask failed = tasks.findById(taskId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        assertThat(failed.getLastError()).isEqualTo("IllegalStateException");
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.deleteInvocations() - deletesBefore).isZero();
        // 주입은 이미 해제됐지만 FAILED는 자동 재개하지 않는다.
        worker.runOnce();
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);

        assertThat(success(false, "status").path("failed").asLong()).isEqualTo(1);
        JsonNode failures = success(false, "failed");
        assertThat(failures.size()).isEqualTo(1);
        assertThat(failures.get(0).path("taskId").asLong()).isEqualTo(taskId);
        assertThat(failures.get(0).path("attempts").asInt()).isEqualTo(3);
        assertThat(failures.get(0).path("retryCount").asInt()).isZero();

        String request = UUID.randomUUID().toString();
        String[] retry = {"retry", taskId.toString(), "0", request, "Failure injection removed; test-only recovery"};
        var refused = R2DeletionManualRecoveryProcess.run(target, directory, false, retry);
        assertThat(refused.exitCode()).isEqualTo(2);
        assertThat(operations.history(taskId)).isEmpty();
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);

        JsonNode audit = success(true, retry);
        R2DeletionTask pending = tasks.findById(taskId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(pending.getAttemptCount()).isZero();
        assertThat(pending.getLastError()).isNull();
        assertThat(pending.getCreatedAt()).isEqualTo(failed.getCreatedAt());
        assertThat(pending.getFileUrl()).isEqualTo(failed.getFileUrl());
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.deleteInvocations() - deletesBefore).isZero();
        assertThat(audit.path("previousAttempts").asInt()).isEqualTo(3);
        assertThat(audit.path("previousError").asText()).isEqualTo("IllegalStateException");
        assertThat(audit.path("requestId").asText()).isEqualTo(request);
        assertThat(audit.path("retryNumber").asInt()).isEqualTo(1);
        assertThat(operations.history(taskId)).hasSize(1);
        assertThat(success(true, retry)).isEqualTo(audit);
        assertThat(tasks.findById(taskId).orElseThrow().getAvailableAt()).isEqualTo(pending.getAvailableAt());

        // CLI는 실제 UTC Clock을 쓴다. 작업자 Clock을 저장된 예약 시각 뒤로 맞춘다.
        // 실제 시간 대기/스케줄러 복구 시간을 측정하는 테스트는 아니다.
        long secondsUntilDue = Duration.between(clock.instant(), pending.getAvailableAt()).getSeconds();
        if (secondsUntilDue >= 0) clock.advance(secondsUntilDue + 1);
        assertThat(taskService.readyIds()).contains(taskId);
        worker.runOnce();
        R2DeletionTask completed = tasks.findById(taskId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.deleteInvocations() - deletesBefore).isEqualTo(1);

        // 완료 후 같은 요청을 다시 보내도 새 삭제 예약을 만들지 않는다.
        assertThat(success(true, retry)).isEqualTo(audit);
        worker.runOnce();
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(storage.deleteInvocations() - deletesBefore).isEqualTo(1);
        JsonNode history = success(false, "history", taskId.toString());
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0)).isEqualTo(audit);
        assertThat(success(false, "failed").size()).isZero();
        JsonNode summary = success(false, "status");
        assertThat(summary.path("pending").asLong()).isZero();
        assertThat(summary.path("processing").asLong()).isZero();
        assertThat(summary.path("failed").asLong()).isZero();
        assertThat(summary.path("completed").asLong()).isEqualTo(1);
        System.out.printf("R2 MANUAL LIVE: taskId=%d, initialAttempts=3, FAILED -> CLI PENDING -> COMPLETED, "
                + "dbPhoto=0, remainingFile=0, auditRows=1, replayAfterCompletion=unchanged, "
                + "deleteSdkCallsBeforeCleanup=1; injected before HTTP, worker called with adjusted test Clock%n", taskId);
    }

    private JsonNode success(boolean approveWrite, String... arguments) throws Exception {
        var result = R2DeletionManualRecoveryProcess.run(target, directory, approveWrite, arguments);
        assertThat(result.exitCode()).withFailMessage("Test CLI failed: %s", result.output()).isZero();
        return result.json();
    }
}
