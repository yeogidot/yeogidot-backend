package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit live opt-in only. No Boot application, scheduler or production properties. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_LIVE_TEST", matches = "true")
@SpringJUnitConfig(R2DeletionLiveTestConfiguration.class)
@TestPropertySource(properties = {"r2.mysql.suffix=photo", "r2.bucket=yeogidot-r2-delete-test",
        "r2.public-url=https://r2-test.invalid"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class R2DeletionLiveIntegrationTest {
    @Autowired private R2LiveTestStorage storage;
    @Autowired private R2DeletionLiveTestConfiguration.FaultInjectingGcsService gcs;
    @Autowired private PhotoService photos;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private R2DeletionTaskRepository tasks;
    @Autowired private R2DeletionWorker worker;
    @Autowired private R2DeletionTaskService taskService;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach void prepare() throws Exception {
        R2DeletionMySqlTestConfiguration.verify(dataSource);
        assertThat(tasks.findAll()).allMatch(t -> t.getStatus() == R2DeletionTask.Status.COMPLETED);
        clock.reset();
        gcs.failNext.set(false);
    }

    @AfterEach void cleanupFixtures() {
        gcs.failNext.set(false);
        storage.cleanup(); // After assertions; cleanup cannot make the scenario pass.
    }

    @Test void 실제_파일을_삭제하고_작업을_완료한다() {
        Fixture f = fixture();
        Long taskId = deletePhoto(f);
        int callsBefore = storage.deleteInvocations();
        assertThat(storage.exists(f.key())).isTrue();
        worker.runOnce();
        completed(taskId, f.key(), 1);
        assertThat(storage.deleteInvocations() - callsBefore).isEqualTo(1);
        worker.runOnce();
        assertThat(storage.deleteInvocations() - callsBefore).isEqualTo(1);
        System.out.println("R2 LIVE normal: dbPhoto=0, file=0, task=COMPLETED, attempts=1, deleteSdkCalls=1");
    }

    @Test void 실제_파일이_이미_없어도_재처리를_완료한다() {
        Fixture f = fixture();
        Long taskId = deletePhoto(f);
        int callsBefore = storage.deleteInvocations();
        var claim = taskService.claim(taskId).orElseThrow();
        gcs.deleteFileStrict(claim.fileUrl());
        assertThat(storage.exists(f.key())).isFalse();
        // Simulate a missing completion record, not an actual JVM termination.
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.PROCESSING);
        clock.advance(61);
        worker.runOnce();
        completed(taskId, f.key(), 2);
        assertThat(storage.deleteInvocations() - callsBefore).isEqualTo(2);
        System.out.println("R2 LIVE already absent: file=0, task=COMPLETED, attempts=2, deleteSdkCalls=2");
    }

    @Test void 삭제_호출_실패를_주입한_뒤_재시도로_실제_파일을_삭제한다() {
        Fixture f = fixture();
        Long taskId = deletePhoto(f);
        int callsBefore = storage.deleteInvocations();
        gcs.failNext.set(true);
        worker.runOnce();
        var pending = tasks.findById(taskId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getAvailableAt()).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(storage.exists(f.key())).isTrue();
        assertThat(storage.deleteInvocations() - callsBefore).isZero();
        worker.runOnce(); // Not yet due.
        assertThat(tasks.findById(taskId).orElseThrow().getAttemptCount()).isEqualTo(1);
        clock.advance(1);
        worker.runOnce();
        completed(taskId, f.key(), 2);
        assertThat(storage.deleteInvocations() - callsBefore).isEqualTo(1);
        System.out.println("R2 LIVE injected failure: retainedFile=1 -> 0, task=COMPLETED, attempts=2, deleteSdkCalls=1");
    }

    private Fixture fixture() {
        String key = storage.uploadFixture();
        assertThat(storage.exists(key)).isTrue();
        return new TransactionTemplate(transactions).execute(status -> {
            User user = User.create(UUID.randomUUID() + "@example.invalid", "test-only-password");
            entityManager.persist(user);
            Photo photo = Photo.builder().user(user).filePath(R2LiveTestStorage.URL_BASE + "/" + key)
                    .originalName("test-only.txt").takenAt(LocalDateTime.of(2026, 9, 20, 12, 0)).build();
            entityManager.persist(photo);
            return new Fixture(photo.getId(), user.getId(), key);
        });
    }

    private Long deletePhoto(Fixture f) {
        assertThat(photos.deletePhoto(f.photoId(), f.userId())).isEqualTo(f.photoId());
        assertThat(photoRepository.existsById(f.photoId())).isFalse();
        var matching = tasks.findAll().stream().filter(t -> t.getFileUrl()
                .equals(R2LiveTestStorage.URL_BASE + "/" + f.key())).toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        return matching.getFirst().getId();
    }

    private void completed(Long id, String key, int attempts) {
        var task = tasks.findById(id).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(task.getAttemptCount()).isEqualTo(attempts);
        assertThat(storage.exists(key)).isFalse();
    }

    private record Fixture(Long photoId, Long userId, String key) { }
}
