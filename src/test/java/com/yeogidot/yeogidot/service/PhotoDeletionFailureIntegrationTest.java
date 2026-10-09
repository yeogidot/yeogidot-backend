package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 변경 전 재현은 d5513cf에 보관한다. 같은 입력 조건에 작업 저장/재시도 검증을 추가했다.
 *
 * application*.properties, 운영 설정, 실제 R2 클라이언트를 로드하지 않는다.
 * DB는 코드로 생성한 메모리 H2, 저장소는 메모리 Map과 GcsService mock이다.
 * 시간은 제어 가능한 Clock으로 진행한다. 실제 프로세스 종료는 별도 테스트에서 검증한다.
 */
@SpringJUnitConfig(PhotoDeletionFailureIntegrationTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoDeletionFailureIntegrationTest {

    @Autowired private PhotoService photoService;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GcsService gcsService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private R2DeletionTaskService tasks;
    @Autowired private R2DeletionWorker worker;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @MockitoSpyBean private R2DeletionTaskRepository taskRepository;
    @PersistenceContext private EntityManager entityManager;

    private final Map<String, byte[]> storedFiles = new HashMap<>();
    private final AtomicBoolean deletionObservedCommittedDb = new AtomicBoolean();

    @BeforeEach
    void resetFakeStorageAndCheckDatabase() throws Exception {
        verifyTestDatabase(dataSource);
        reset(gcsService);
        storedFiles.clear();
        deletionObservedCommittedDb.set(false);
        clock.reset();
        taskRepository.deleteAll();
    }

    protected void verifyTestDatabase(DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

    @ParameterizedTest(name = "실패 후 재시도: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void R2_삭제가_실패해도_작업을_보관하고_재시도로_파일을_삭제한다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, true);

        // Spring 프록시의 실제 @Transactional을 사용한다. 테스트 자체에는 트랜잭션이 없다.
        assertThat(photoService.deletePhoto(fixture.photoId(), fixture.userId()))
                .isEqualTo(fixture.photoId());
        verify(gcsService, never()).deleteFileStrict(anyString());
        Long taskId = onlyTask().getId();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        worker.runOnce();

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(deletionObservedCommittedDb.get()).isTrue();
        assertThat(storedFiles).containsKey(fixture.url());
        verify(gcsService, times(1)).deleteFileStrict(fixture.url());
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(1);
        assertThat(onlyTask().getAvailableAt()).isEqualTo(clock.instant().plusSeconds(1));
        worker.runOnce(); // 예약 시각 전에는 재실행하지 않는다.
        verify(gcsService, times(1)).deleteFileStrict(fixture.url());

        configureStorageDelete(fixture, false);
        clock.advance(1);
        worker.runOnce();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(2);
        assertThat(storedFiles).doesNotContainKey(fixture.url());
        verify(gcsService, times(2)).deleteFileStrict(fixture.url());
        System.out.printf("R2 recovery: assigned=%s, dbPhoto=0, fileAfterFailure=1, fileAfterRetry=0, deleteCalls=2%n",
                assignedToDay);
    }

    @ParameterizedTest(name = "정상 삭제 대조군: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void R2_삭제가_성공하면_DB와_파일이_모두_삭제된다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, false);

        assertThat(photoService.deletePhoto(fixture.photoId(), fixture.userId()))
                .isEqualTo(fixture.photoId());
        verify(gcsService, never()).deleteFileStrict(anyString());
        assertThat(storedFiles).containsKey(fixture.url());
        worker.runOnce();

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(deletionObservedCommittedDb.get()).isTrue();
        assertThat(storedFiles).doesNotContainKey(fixture.url());
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        worker.runOnce(); // 완료된 작업은 다시 호출하지 않는다.
        verify(gcsService, times(1)).deleteFileStrict(fixture.url());
    }

    @ParameterizedTest(name = "롤백 대조군: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void DB가_롤백되면_사진과_파일이_보존되고_R2를_호출하지_않는다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, false);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            photoService.deletePhoto(fixture.photoId(), fixture.userId());
            assertThat(photoRepository.existsById(fixture.photoId())).isFalse();
            verify(gcsService, never()).deleteFile(anyString());
            verify(gcsService, never()).deleteFileStrict(anyString());
            assertThat(taskRepository.count()).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isTrue();
        assertThat(storedFiles).containsKey(fixture.url());
        verify(gcsService, never()).deleteFile(anyString());
        assertThat(taskRepository.count()).isZero();
        worker.runOnce();
        verify(gcsService, never()).deleteFileStrict(anyString());
    }

    @Test
    void 작업_저장이_실패하면_사진_삭제도_롤백된다() {
        Fixture fixture = createFixture(true);
        doThrow(new DataIntegrityViolationException("Injected task insert failure"))
                .when(taskRepository).save(any(R2DeletionTask.class));
        assertThatThrownBy(() -> photoService.deletePhoto(fixture.photoId(), fixture.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(photoExistsInNewTransaction(fixture.photoId())).isTrue();
        assertThat(taskRepository.count()).isZero();
        assertThat(storedFiles).containsKey(fixture.url());
        verify(gcsService, never()).deleteFileStrict(anyString());
    }

    @Test
    void 트랜잭션_없이_삭제_작업만_저장할_수_없다() {
        assertThatThrownBy(() -> tasks.enqueue("https://r2-test.invalid/test.jpg"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(taskRepository.count()).isZero();
    }

    @ParameterizedTest(name = "반복 실패 한도: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void 반복_실패는_간격을_늘리고_한도에서_중단한다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, true);
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        Long taskId = onlyTask().getId();
        var createdAt = onlyTask().getCreatedAt();
        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(storedFiles).containsKey(fixture.url());
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(onlyTask().getAttemptCount()).isZero();
        verify(gcsService, never()).deleteFileStrict(anyString());

        worker.runOnce();
        assertThat(deletionObservedCommittedDb.get()).isTrue();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(1);
        assertThat(onlyTask().getAvailableAt()).isEqualTo(clock.instant().plusSeconds(1));
        worker.runOnce(); // 첫 재시도 예약 시각 전에는 외부 삭제를 다시 호출하지 않는다.
        verify(gcsService, times(1)).deleteFileStrict(fixture.url());

        clock.advance(1);
        worker.runOnce();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(2);
        assertThat(onlyTask().getAvailableAt()).isEqualTo(clock.instant().plusSeconds(2));
        worker.runOnce(); // 두 번째 재시도 예약 시각 전에도 호출하지 않는다.
        verify(gcsService, times(2)).deleteFileStrict(fixture.url());

        clock.advance(2);
        worker.runOnce();
        R2DeletionTask failedTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(storedFiles).containsKey(fixture.url());
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(failedTask.getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        assertThat(failedTask.getFileUrl()).isEqualTo(fixture.url());
        assertThat(failedTask.getLastError()).isEqualTo("IllegalStateException");
        assertThat(failedTask.getAttemptCount()).isEqualTo(3);
        assertThat(failedTask.getCreatedAt()).isEqualTo(createdAt);
        assertThat(failedTask.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(failedTask.getCompletedAt()).isNull();
        assertThat(failedTask.getLeaseToken()).isNull();
        verify(gcsService, times(3)).deleteFileStrict(fixture.url());

        // 외부 장애가 해소돼도 FAILED는 자동 재개하지 않는다. 별도 재처리 기능이 필요한 기준선이다.
        configureStorageDelete(fixture, false);
        clock.advance(3600);
        assertThat(tasks.readyIds()).isEmpty();
        assertThat(tasks.claim(taskId)).isEmpty();
        worker.runOnce();
        verify(gcsService, times(3)).deleteFileStrict(fixture.url());
        R2DeletionTask retainedTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(retainedTask.getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        assertThat(retainedTask.getAttemptCount()).isEqualTo(3);
        assertThat(retainedTask.getLastError()).isEqualTo(failedTask.getLastError());
        assertThat(retainedTask.getUpdatedAt()).isEqualTo(failedTask.getUpdatedAt());
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(storedFiles).containsKey(fixture.url());
        System.out.printf("R2 FAILED baseline: assigned=%s, dbPhoto=0, remainingFile=1, "
                        + "status=FAILED, attempts=3, deleteCalls=3, autoRetryAfterRecovery=0%n",
                assignedToDay);
    }

    @Test
    void 잘못된_URL은_반복_호출하지_않고_실패로_남긴다() {
        Fixture fixture = createFixture(false);
        doThrow(new IllegalArgumentException("Invalid target"))
                .when(gcsService).deleteFileStrict(fixture.url());
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        worker.runOnce();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        clock.advance(3600);
        worker.runOnce();
        verify(gcsService, times(1)).deleteFileStrict(fixture.url());
    }

    @Test
    void 선점이_유효한_동안_동시_작업자는_같은_파일을_삭제하지_않는다() throws Exception {
        Fixture fixture = createFixture(false);
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        Long id = onlyTask().getId();
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleting.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            storedFiles.remove(fixture.url());
            return null;
        }).when(gcsService).deleteFileStrict(fixture.url());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> worker.process(id));
            try {
                assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> worker.process(id)).get(5, TimeUnit.SECONDS);
                verify(gcsService, times(1)).deleteFileStrict(fixture.url());
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void 만료된_작업자의_완료나_실패는_새_선점을_덮어쓰지_않는다() {
        Fixture fixture = createFixture(false);
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        Long id = onlyTask().getId();
        var oldClaim = tasks.claim(id).orElseThrow();
        assertThat(tasks.claim(id)).isEmpty();
        clock.advance(61);
        var newClaim = tasks.claim(id).orElseThrow();
        tasks.complete(oldClaim);
        tasks.failed(oldClaim, new IllegalStateException());
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.PROCESSING);
        assertThat(onlyTask().getLeaseToken()).isEqualTo(newClaim.token());
        tasks.complete(newClaim);
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
    }

    @Test
    void 선점한_작업이_계속_중단돼도_시도_한도에서_실패로_남긴다() {
        Fixture fixture = createFixture(false);
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        Long id = onlyTask().getId();
        for (int i = 0; i < 3; i++) {
            assertThat(tasks.claim(id)).isPresent();
            clock.advance(61);
        }
        assertThat(tasks.claim(id)).isEmpty();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        assertThat(onlyTask().getAttemptCount()).isEqualTo(3);
    }

    @Test
    void 파일_삭제후_완료기록이_없어도_재처리할_수_있다() {
        Fixture fixture = createFixture(false);
        configureStorageDelete(fixture, false);
        photoService.deletePhoto(fixture.photoId(), fixture.userId());
        var claim = tasks.claim(onlyTask().getId()).orElseThrow();
        gcsService.deleteFileStrict(claim.fileUrl());
        // 완료 상태를 기록하기 전에 중단된 상황. 이미 없는 파일의 재삭제는 성공으로 취급한다.
        clock.advance(61);
        worker.runOnce();
        assertThat(onlyTask().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(storedFiles).isEmpty();
        verify(gcsService, times(2)).deleteFileStrict(fixture.url());
    }

    private R2DeletionTask onlyTask() {
        var all = taskRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private Fixture createFixture(boolean assignedToDay) {
        Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> {
            String token = UUID.randomUUID().toString();
            User owner = User.create(token + "@example.invalid", "test-only-password");
            entityManager.persist(owner);

            TravelDay day = null;
            if (assignedToDay) {
                Travel travel = Travel.builder().user(owner).title("R2 failure reproduction")
                        .startDate(LocalDate.of(2026, 9, 17))
                        .endDate(LocalDate.of(2026, 9, 17)).build();
                entityManager.persist(travel);
                day = TravelDay.builder().travel(travel).dayNumber(1)
                        .date(LocalDate.of(2026, 9, 17)).build();
                entityManager.persist(day);
            }

            String url = "https://r2-test.invalid/" + token + ".jpg";
            Photo photo = Photo.builder().user(owner).travelDay(day).filePath(url)
                    .originalName("test-only.jpg")
                    .takenAt(LocalDateTime.of(2026, 9, 17, 12, 0)).build();
            entityManager.persist(photo);
            return new Fixture(photo.getId(), owner.getId(), url);
        });
        assertThat(fixture).isNotNull();
        storedFiles.put(fixture.url(), new byte[]{1, 2, 3});
        assertThat(photoExistsInNewTransaction(fixture.photoId())).isTrue();
        return fixture;
    }

    private void configureStorageDelete(Fixture fixture, boolean fail) {
        doAnswer(invocation -> {
            // afterCommit의 기존 영속성 컨텍스트가 아닌 새 트랜잭션에서 커밋 결과를 확인한다.
            deletionObservedCommittedDb.set(!photoExistsInNewTransaction(fixture.photoId()));
            if (fail) {
                throw new IllegalStateException("Injected R2 delete failure (no external request)");
            }
            storedFiles.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(gcsService).deleteFileStrict(fixture.url());
    }

    private boolean photoExistsInNewTransaction(Long photoId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(transaction.execute(status -> photoRepository.existsById(photoId)));
    }

    private record Fixture(Long photoId, Long userId, String url) { }

    @Configuration(proxyBeanMethods = false)
    @Import(R2DeletionTestConfiguration.class)
    static class TestConfig {
        @Bean(destroyMethod = "shutdown")
        org.springframework.jdbc.datasource.embedded.EmbeddedDatabase dataSource() {
            // 외부 환경 변수/운영 datasource 설정과 무관하게 메모리 H2만 생성한다.
            return new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
        }

    }
}
