package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 현재 동작을 기록하는 재현 테스트: DB 커밋 후 R2 삭제가 실패하면 파일이 남는다.
 * 테스트 통과는 복구 완료가 아니라 해당 실패 상황의 재현 성공을 의미한다.
 *
 * application*.properties, 운영 설정, 실제 R2 클라이언트를 로드하지 않는다.
 * DB는 코드로 생성한 메모리 H2, 저장소는 메모리 Map과 GcsService mock이다.
 * 서버 재시작/시간 경과 후 복구나 실제 R2 장애 자체는 이 테스트의 검증 범위가 아니다.
 */
@SpringJUnitConfig(PhotoDeletionFailureIntegrationTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoDeletionFailureIntegrationTest {

    @Autowired private PhotoService photoService;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GcsService gcsService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    private final Map<String, byte[]> storedFiles = new HashMap<>();
    private final AtomicBoolean deletionObservedCommittedDb = new AtomicBoolean();

    @BeforeEach
    void resetFakeStorageAndCheckDatabase() throws Exception {
        reset(gcsService);
        storedFiles.clear();
        deletionObservedCommittedDb.set(false);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

    @ParameterizedTest(name = "R2 실패 재현: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void R2_삭제가_실패하면_DB는_삭제되지만_파일은_남는다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, true);

        // Spring 프록시의 실제 @Transactional을 사용한다. 테스트 자체에는 트랜잭션이 없다.
        assertThat(photoService.deletePhoto(fixture.photoId(), fixture.userId()))
                .isEqualTo(fixture.photoId());

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(deletionObservedCommittedDb.get()).isTrue();
        assertThat(storedFiles).containsKey(fixture.url());
        verify(gcsService, times(1)).deleteFile(fixture.url());
        System.out.printf("R2 failure baseline: assigned=%s, dbPhoto=0, remainingFile=1, deleteCalls=1%n",
                assignedToDay);
    }

    @ParameterizedTest(name = "정상 삭제 대조군: 여행 일차 소속={0}")
    @ValueSource(booleans = {false, true})
    void R2_삭제가_성공하면_DB와_파일이_모두_삭제된다(boolean assignedToDay) {
        Fixture fixture = createFixture(assignedToDay);
        configureStorageDelete(fixture, false);

        assertThat(photoService.deletePhoto(fixture.photoId(), fixture.userId()))
                .isEqualTo(fixture.photoId());

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isFalse();
        assertThat(deletionObservedCommittedDb.get()).isTrue();
        assertThat(storedFiles).doesNotContainKey(fixture.url());
        verify(gcsService, times(1)).deleteFile(fixture.url());
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
            status.setRollbackOnly();
        });

        assertThat(photoExistsInNewTransaction(fixture.photoId())).isTrue();
        assertThat(storedFiles).containsKey(fixture.url());
        verify(gcsService, never()).deleteFile(anyString());
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
        }).when(gcsService).deleteFile(fixture.url());
    }

    private boolean photoExistsInNewTransaction(Long photoId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(transaction.execute(status -> photoRepository.existsById(photoId)));
    }

    private record Fixture(Long photoId, Long userId, String url) { }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = PhotoRepository.class)
    @Import({PhotoService.class, OwnershipValidator.class})
    static class TestConfig {
        @Bean(destroyMethod = "shutdown")
        org.springframework.jdbc.datasource.embedded.EmbeddedDatabase dataSource() {
            // 외부 환경 변수/운영 datasource 설정과 무관하게 메모리 H2만 생성한다.
            return new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Photo.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean GcsService gcsService() { return mock(GcsService.class); }
        @Bean GeoCodingService geoCodingService() { return mock(GeoCodingService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
