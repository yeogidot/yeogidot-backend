package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 코드로 생성한 H2와 가짜 저장소만 사용한다. HTTP/운영 설정을 로드하지 않는다. */
@SpringJUnitConfig(R2DeletionOperationsIntegrationTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2DeletionOperationsIntegrationTest {
    @Autowired private R2DeletionOperationsService operations;
    @Autowired private R2DeletionTaskService tasks;
    @Autowired private R2DeletionWorker worker;
    @Autowired private PhotoService photos;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private R2DeletionTaskRepository repository;
    @Autowired private GcsService storage;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource source;
    @PersistenceContext private EntityManager em;
    private JdbcTemplate jdbc;
    private final AtomicBoolean failDeletes = new AtomicBoolean(true);
    private final Set<String> files = ConcurrentHashMap.newKeySet();

    @BeforeEach void isolate() throws Exception {
        verifyDatabase(source);
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM r2_deletion_retry_audit");
        repository.deleteAll();
        clock.reset(); files.clear(); failDeletes.set(true); reset(storage);
        doAnswer(call -> {
            if (failDeletes.get()) throw new IllegalStateException("Injected delete failure; no HTTP");
            files.remove(call.getArgument(0, String.class));
            return null;
        }).when(storage).deleteFileStrict(anyString());
    }

    protected void verifyDatabase(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

    @Test void 실패_조회에서_사진은_없지만_파일과_작업_정보는_남는다() {
        assertThat(operations.summary().failed()).isZero();
        Fixture fixture = failedPhoto();
        assertThat(photoRepository.existsById(fixture.photoId())).isFalse();
        assertThat(files).contains(fixture.url());
        assertThat(operations.summary().failed()).isEqualTo(1);
        var failure = operations.failedTasks(0, 20).getFirst();
        assertThat(failure.taskId()).isEqualTo(fixture.taskId());
        assertThat(failure.fileUrl()).isEqualTo(fixture.url());
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.error()).isEqualTo("IllegalStateException");
        assertThat(failure.failedAt()).isEqualTo(clock.instant());
        assertThat(failure.retryCount()).isZero();
        assertThat(operations.failedTasks(fixture.taskId(), 20)).isEmpty();
        assertThatThrownBy(() -> operations.failedTasks(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.failedTasks(0, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 재처리_예약과_이력을_저장하고_기존_작업자가_파일을_삭제한다() {
        Fixture fixture = failedPhoto();
        var original = repository.findById(fixture.taskId()).orElseThrow();
        clock.advance(1);
        String request = requestId();
        var audit = operations.retryFailed(fixture.taskId(), 0, request, "operator", "원인 확인 후 권한 복구");
        var pending = repository.findById(fixture.taskId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(pending.getAttemptCount()).isZero();
        assertThat(pending.getLeaseToken()).isNull();
        assertThat(pending.getLastError()).isNull();
        assertThat(pending.getCompletedAt()).isNull();
        assertThat(pending.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(pending.getFileUrl()).isEqualTo(fixture.url());
        assertThat(pending.getAvailableAt()).isEqualTo(clock.instant());
        assertThat(audit.previousAttempts()).isEqualTo(3);
        assertThat(audit.previousError()).isEqualTo("IllegalStateException");
        assertThat(audit.failedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(audit.requestedAt()).isEqualTo(clock.instant());
        assertThat(audit.operator()).isEqualTo("operator");
        assertThat(audit.reason()).isEqualTo("원인 확인 후 권한 복구");
        assertThat(operations.history(fixture.taskId())).containsExactly(audit);
        // 예약만으로는 파일이 삭제되지 않는다.
        assertThat(files).contains(fixture.url());
        verify(storage, times(3)).deleteFileStrict(fixture.url());
        failDeletes.set(false);
        worker.runOnce();
        assertThat(repository.findById(fixture.taskId()).orElseThrow().getStatus())
                .isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(files).doesNotContain(fixture.url());
        assertThat(operations.history(fixture.taskId()).getFirst().previousError())
                .isEqualTo("IllegalStateException");
    }

    @Test void 동일_요청은_완료후에도_같은_이력을_반환하고_새_예약을_만들지_않는다() {
        Fixture fixture = failedPhoto();
        String request = requestId();
        var audit = operations.retryFailed(fixture.taskId(), 0, request, "operator", "권한 복구");
        failDeletes.set(false); worker.runOnce();
        assertThat(operations.retryFailed(fixture.taskId(), 0, request, "operator", "권한 복구")).isEqualTo(audit);
        worker.runOnce();
        assertThat(operations.history(fixture.taskId())).hasSize(1);
        verify(storage, times(4)).deleteFileStrict(fixture.url());
    }

    @Test void 원인이_해결되지_않으면_다시_한도에서_실패하고_오래된_회차_요청은_거절한다() {
        Fixture fixture = failedPhoto();
        operations.retryFailed(fixture.taskId(), 0, requestId(), "operator", "확인 후 재시도");
        exhaustAttempts();
        assertThat(operations.failedTasks(0, 20).getFirst().retryCount()).isEqualTo(1);
        assertThat(operations.history(fixture.taskId())).hasSize(1);
        assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, requestId(), "operator", "오래된 요청"))
                .isInstanceOf(IllegalStateException.class);
        var next = operations.retryFailed(fixture.taskId(), 1, requestId(), "operator", "새 원인 조치");
        assertThat(next.retryNumber()).isEqualTo(2);
        assertThat(operations.history(fixture.taskId())).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = R2DeletionTask.Status.class, names = {"PENDING", "PROCESSING", "COMPLETED"})
    void 실패가_아닌_작업은_재처리하지_않는다(R2DeletionTask.Status state) {
        Long id = new TransactionTemplate(transactionManager).execute(s -> tasks.enqueue("https://r2-test.invalid/other"));
        if (state == R2DeletionTask.Status.PROCESSING) tasks.claim(id);
        if (state == R2DeletionTask.Status.COMPLETED) { failDeletes.set(false); worker.process(id); }
        assertThatThrownBy(() -> operations.retryFailed(id, 0, requestId(), "operator", "확인"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(state);
        assertThat(operations.history(id)).isEmpty();
    }

    @Test void 같은_UUID를_다른_내용이나_작업에_사용하지_못한다() {
        Fixture fixture = failedPhoto();
        String request = requestId();
        operations.retryFailed(fixture.taskId(), 0, request, "operator", "복구");
        assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, request, "operator", "다른 사유"))
                .isInstanceOf(IllegalStateException.class);
        Fixture other = failedPhoto();
        assertThatThrownBy(() -> operations.retryFailed(other.taskId(), 0, request, "operator", "복구"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(other.taskId()).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        assertThat(operations.history(other.taskId())).isEmpty();
    }

    @Test void 빈_사유_잘못된_UUID_로그개행_기존트랜잭션_없는작업을_거절한다() {
        Fixture fixture = failedPhoto();
        assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, requestId(), "operator", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, "bad", "operator", "복구"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, requestId(), "operator\nforged", "복구"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(s ->
                operations.retryFailed(fixture.taskId(), 0, requestId(), "operator", "복구")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> operations.retryFailed(Long.MAX_VALUE, 0, requestId(), "operator", "복구"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.history(fixture.taskId())).isEmpty();
    }

    @Test void 감사기록_INSERT가_실패하면_예약변경도_롤백한다() {
        Fixture fixture = failedPhoto();
        // 검증한 전용 테스트 DB의 감사 테이블만 훼손해 UPDATE 다음 INSERT 실패를 유발한다.
        jdbc.execute("ALTER TABLE r2_deletion_retry_audit DROP COLUMN reason");
        try {
            assertThatThrownBy(() -> operations.retryFailed(fixture.taskId(), 0, requestId(), "operator", "복구"))
                    .isInstanceOf(DataAccessException.class);
            var retained = repository.findById(fixture.taskId()).orElseThrow();
            assertThat(retained.getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
            assertThat(retained.getAttemptCount()).isEqualTo(3);
            assertThat(retained.getLastError()).isEqualTo("IllegalStateException");
            assertThat(tasks.readyIds()).isEmpty();
            assertThat(files).contains(fixture.url());
        } finally {
            jdbc.execute("ALTER TABLE r2_deletion_retry_audit ADD COLUMN reason VARCHAR(500) NOT NULL");
        }
    }

    @Test void 다른_UUID의_동시_요청은_하나만_예약한다() throws Exception {
        Fixture fixture = failedPhoto();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> retryAfterSignal(start, fixture, requestId()));
            var b = executor.submit(() -> retryAfterSignal(start, fixture, requestId()));
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(operations.history(fixture.taskId())).hasSize(1);
        assertThat(repository.findById(fixture.taskId()).orElseThrow().getAttemptCount()).isZero();
        verify(storage, times(3)).deleteFileStrict(fixture.url());
    }

    @Test void 동일_UUID의_동시_요청은_같은_예약을_공유한다() throws Exception {
        Fixture fixture = failedPhoto();
        String request = requestId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return operations.retryFailed(fixture.taskId(), 0, request, "operator", "복구"); });
            var b = executor.submit(() -> { start.await(); return operations.retryFailed(fixture.taskId(), 0, request, "operator", "복구"); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(operations.history(fixture.taskId())).hasSize(1);
    }

    @Test void 다른_작업에_같은_UUID를_동시에_쓰면_한쪽_예약은_롤백한다() throws Exception {
        Fixture first = failedPhoto();
        Fixture second = failedPhoto();
        String request = requestId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> retryCrossTask(start, first, request));
            var b = executor.submit(() -> retryCrossTask(start, second, request));
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(operations.summary().pending()).isEqualTo(1);
        assertThat(operations.summary().failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM r2_deletion_retry_audit", Integer.class)).isEqualTo(1);
    }

    private int retryCrossTask(CountDownLatch start, Fixture fixture, String request) throws Exception {
        start.await();
        try { operations.retryFailed(fixture.taskId(), 0, request, "operator", "복구"); return 1; }
        catch (DataAccessException | IllegalStateException conflict) { return 0; }
    }

    private int retryAfterSignal(CountDownLatch start, Fixture fixture, String request) throws Exception {
        start.await();
        try { operations.retryFailed(fixture.taskId(), 0, request, "operator", "복구"); return 1; }
        catch (IllegalStateException rejected) { return 0; }
    }
    private Fixture failedPhoto() {
        String url = "https://r2-test.invalid/" + UUID.randomUUID() + ".jpg";
        Photo photo = new TransactionTemplate(transactionManager).execute(s -> {
            User user = User.create(UUID.randomUUID() + "@example.invalid", "test-only");
            em.persist(user);
            Photo created = Photo.builder().user(user).filePath(url).originalName("test.jpg")
                    .takenAt(LocalDateTime.of(2026, 10, 9, 12, 0)).build();
            em.persist(created); return created;
        });
        files.add(url);
        photos.deletePhoto(photo.getId(), photo.getUser().getId());
        Long id = repository.findAll().stream().filter(t -> t.getFileUrl().equals(url)).findFirst().orElseThrow().getId();
        exhaustAttempts();
        return new Fixture(photo.getId(), id, url);
    }
    private void exhaustAttempts() { worker.runOnce(); clock.advance(1); worker.runOnce(); clock.advance(2); worker.runOnce(); }
    private String requestId() { return UUID.randomUUID().toString(); }
    private record Fixture(Long photoId, Long taskId, String url) { }

    @Configuration(proxyBeanMethods = false)
    @Import({R2DeletionTestConfiguration.class, R2DeletionOperationsService.class})
    static class TestConfig {
        @Bean(destroyMethod = "shutdown")
        org.springframework.jdbc.datasource.embedded.EmbeddedDatabase dataSource() {
            var source = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
            new ResourceDatabasePopulator(new ClassPathResource("db/manual/20261009_create_r2_deletion_retry_audit.sql"))
                    .execute(source);
            return source;
        }
    }
}
