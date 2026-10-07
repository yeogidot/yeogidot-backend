package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.exception.BadRequestException;
import com.yeogidot.yeogidot.exception.CommentSelectionRequiredException;
import com.yeogidot.yeogidot.exception.ResourceNotFoundException;
import com.yeogidot.yeogidot.repository.*;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 메모리 H2와 실제 서비스 트랜잭션만 사용한다. 운영 설정, Redis, R2를 로드하지 않는다. */
@SpringJUnitConfig(PhotoCommentIntegrationTest.Config.class)
@TestPropertySource(properties = "app.frontend.base-url=https://example.test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoCommentIntegrationTest {
    @Autowired PhotoService photos;
    @Autowired TravelService travels;
    @Autowired CommentRepository comments;
    @Autowired PhotoRepository photoRepository;
    @Autowired EntityManagerFactory factory;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void samePersonCanWriteThreeCommentsOnTheSamePhoto() {
        Fixture f = fixture();
        Long first = write(f, "first");
        Long second = write(f, "second");
        Long third = write(f, "third");
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        assertThat(content(first)).isEqualTo("first");
        assertThat(content(second)).isEqualTo("second");
        assertThat(content(third)).isEqualTo("third");
        Comment stored = comments.findByIdAndPhotoId(second, f.photoId()).orElseThrow();
        assertThat(stored.getWriter().getId()).isEqualTo(f.writer().getId());
        assertThat(stored.getCreatedDate()).isNotNull();
    }

    @Test
    void updateChangesOnlyTheSelectedComment() {
        Fixture f = fixture();
        Long first = write(f, "first");
        Long second = write(f, "second");
        Long third = write(f, "third");
        photos.updateComment(f.photoId(), second, request("changed"), f.writer());
        assertThat(content(first)).isEqualTo("first");
        assertThat(content(second)).isEqualTo("changed");
        assertThat(content(third)).isEqualTo("third");
    }

    @Test
    void deleteRemovesOnlyTheSelectedComment() {
        Fixture f = fixture();
        Long first = write(f, "first");
        Long second = write(f, "second");
        Long third = write(f, "third");
        photos.deleteComment(f.photoId(), second, f.writer());
        assertThat(comments.existsById(second)).isFalse();
        assertThat(content(first)).isEqualTo("first");
        assertThat(content(third)).isEqualTo("third");
        assertThat(photoRepository.existsById(f.photoId())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedUserCannotModifyOrDeleteAComment(boolean delete) {
        Fixture f = fixture();
        Long id = write(f, "unchanged");
        assertThatThrownBy(() -> mutate(f.photoId(), id, f.other(), delete))
                .isInstanceOf(SecurityException.class);
        assertThat(content(id)).isEqualTo("unchanged");
    }

    @Test
    void photoOwnerCanDeleteAnotherPersonsCommentButCannotEditIt() {
        Fixture f = fixture();
        Long id = write(f, "original");
        assertThatThrownBy(() -> photos.updateComment(f.photoId(), id, request("changed"), f.owner()))
                .isInstanceOf(SecurityException.class);
        assertThat(content(id)).isEqualTo("original");
        photos.deleteComment(f.photoId(), id, f.owner());
        assertThat(comments.existsById(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commentCannotBeAddressedThroughAnotherPhoto(boolean delete) {
        Fixture f = fixture();
        Long id = write(f, "unchanged");
        assertThatThrownBy(() -> mutate(f.otherPhotoId(), id, f.writer(), delete))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(content(id)).isEqualTo("unchanged");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCommentIsNotFound(boolean delete) {
        Fixture f = fixture();
        assertThatThrownBy(() -> mutate(f.photoId(), Long.MAX_VALUE, f.writer(), delete))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyApiStillHandlesExactlyOneComment(boolean delete) {
        Fixture f = fixture();
        Long id = write(f, "original");
        mutateLegacy(f.photoId(), f.writer(), delete);
        if (delete) {
            assertThat(comments.existsById(id)).isFalse();
        } else {
            assertThat(content(id)).isEqualTo("changed");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyApiRejectsMultipleCommentsWithoutChangingAnyOfThem(boolean delete) {
        Fixture f = fixture();
        Long first = write(f, "first");
        Long second = write(f, "second");
        Long third = write(f, "third");
        assertThatThrownBy(() -> mutateLegacy(f.photoId(), f.writer(), delete))
                .isInstanceOf(CommentSelectionRequiredException.class);
        assertThat(content(first)).isEqualTo("first");
        assertThat(content(second)).isEqualTo("second");
        assertThat(content(third)).isEqualTo("third");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyApiKeepsTheExistingMissingCommentError(boolean delete) {
        Fixture f = fixture();
        assertThatThrownBy(() -> mutateLegacy(f.photoId(), f.writer(), delete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해당 사진에 댓글이 존재하지 않습니다.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void emptyContentCannotBeCreatedOrUsedToOverwriteExistingContent(String content) {
        Fixture f = fixture();
        Long id = write(f, "original");
        long before = comments.count();
        assertThatThrownBy(() -> photos.createComment(f.photoId(), request(content), f.writer()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> photos.updateComment(f.photoId(), id, request(content), f.writer()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> photos.updateCommentByPhotoId(f.photoId(), request(content), f.writer()))
                .isInstanceOf(BadRequestException.class);
        assertThat(comments.count()).isEqualTo(before);
        assertThat(content(id)).isEqualTo("original");
    }

    @Test
    void nullRequestIsRejected() {
        Fixture f = fixture();
        assertThatThrownBy(() -> photos.createComment(f.photoId(), null, f.writer()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingPhotoCannotReceiveAComment() {
        Fixture f = fixture();
        assertThatThrownBy(() -> photos.createComment(Long.MAX_VALUE, request("text"), f.writer()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void travelResponseIncludesAllCommentsAndWriterInfoWithoutExtraWriterQueries() throws Exception {
        Fixture f = fixture();
        write(f, "first");
        write(f, "second");
        photos.createComment(f.photoId(), request("legacy writer"), f.other());

        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        TravelDto.DetailResponse detail = travels.getTravelDetail(f.travelId(), f.owner());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4L);
        List<TravelDto.CommentDetail> result = detail.getDays().getFirst().getPhotos().getFirst().getComments();
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getWriterId()).isEqualTo(f.writer().getId());
        assertThat(result.get(0).getNickname()).isEqualTo(f.writer().getNickname());
        assertThat(result.get(2).getWriterId()).isEqualTo(f.other().getId());
        assertThat(result.get(2).getNickname()).isNull();
        // DTO는 트랜잭션 종료 후에도 직렬화되며 이메일/비밀번호/닉네임 키를 포함하지 않는다.
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertThat(json).contains("\"writerId\"", "\"nickname\"")
                .doesNotContain("\"email\"", "\"password\"", "\"nicknameKey\"");

        TravelDto.DetailResponse shared = travels.getTravelByShareToken(f.shareToken());
        assertThat(shared.getDays().getFirst().getPhotos().getFirst().getComments())
                .usingRecursiveComparison().isEqualTo(result);
    }

    @Test
    void concurrentRegistrationsByTheSameWriterAreBothPreserved() throws Exception {
        Fixture f = fixture();
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return write(f, "first"); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return write(f, "second"); });
            Long firstId = first.get(10, TimeUnit.SECONDS);
            Long secondId = second.get(10, TimeUnit.SECONDS);
            assertThat(firstId).isNotEqualTo(secondId);
            assertThat(content(firstId)).isEqualTo("first");
            assertThat(content(secondId)).isEqualTo("second");
        }
    }

    @Test
    void registrationWaitsUntilLegacySingleCommentMutationCommits() throws Exception {
        Fixture f = fixture();
        Long original = write(f, "original");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registrationStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var legacy = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    photos.updateCommentByPhotoId(f.photoId(), request("changed"), f.writer());
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return true;
                }));
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var registration = pool.submit(() -> {
                    registrationStarted.countDown();
                    return write(f, "new comment");
                });
                assertThat(registrationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                // 구버전 단건 처리의 트랜잭션이 끝나기 전에는 두 번째 댓글 등록이 완료되지 않는다.
                assertThatThrownBy(() -> registration.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(legacy.get(5, TimeUnit.SECONDS)).isTrue();
                Long added = registration.get(5, TimeUnit.SECONDS);
                assertThat(content(original)).isEqualTo("changed");
                assertThat(content(added)).isEqualTo("new comment");
            } finally {
                release.countDown();
            }
        }
    }

    private Long write(Fixture f, String content) {
        return photos.createComment(f.photoId(), request(content), f.writer());
    }

    private String content(Long id) {
        return comments.findById(id).orElseThrow().getContent();
    }

    private TravelDto.CommentRequest request(String content) {
        return new TravelDto.CommentRequest(content);
    }

    private void mutate(Long photoId, Long commentId, User user, boolean delete) {
        if (delete) photos.deleteComment(photoId, commentId, user);
        else photos.updateComment(photoId, commentId, request("changed"), user);
    }

    private void mutateLegacy(Long photoId, User user, boolean delete) {
        if (delete) photos.deleteCommentByPhotoId(photoId, user);
        else photos.updateCommentByPhotoId(photoId, request("changed"), user);
    }

    private Fixture fixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User owner = persistUser("owner-" + suffix, "Owner_" + suffix);
            User writer = persistUser("writer-" + suffix, "Writer_" + suffix);
            User other = persistUser("other-" + suffix, null);
            LocalDate date = LocalDate.of(2026, 10, 7);
            Travel travel = Travel.builder().user(owner).title("comments-" + suffix)
                    .startDate(date).endDate(date).shareUrl("https://example.test/share/" + suffix).build();
            entityManager.persist(travel);
            TravelDay day = TravelDay.builder().travel(travel).dayNumber(1).date(date).build();
            entityManager.persist(day);
            Photo photo = Photo.builder().user(owner).travelDay(day).filePath("https://example.test/" + suffix)
                    .takenAt(LocalDateTime.of(2026, 10, 7, 12, 0)).build();
            entityManager.persist(photo);
            Photo otherPhoto = Photo.builder().user(owner).filePath("https://example.test/other-" + suffix)
                    .takenAt(LocalDateTime.of(2026, 10, 7, 12, 0)).build();
            entityManager.persist(otherPhoto);
            return new Fixture(owner, writer, other, photo.getId(), otherPhoto.getId(), travel.getId(), suffix);
        });
    }

    private User persistUser(String name, String nickname) {
        User user = User.builder().email(name + "@example.test").password("test-only")
                .nickname(nickname).build();
        entityManager.persist(user);
        return user;
    }

    private record Fixture(User owner, User writer, User other, Long photoId, Long otherPhotoId,
                           Long travelId, String shareToken) {}

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = CommentRepository.class)
    @Import({PhotoService.class, TravelService.class, OwnershipValidator.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:comments-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setPackagesToScan(User.class.getPackageName());
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.generate_statistics", "true"));
            return emf;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
        @Bean GcsService gcsService() { return mock(GcsService.class); }
        @Bean GeoCodingService geoCodingService() { return mock(GeoCodingService.class); }
        @Bean R2DeletionTaskService r2DeletionTaskService() { return mock(R2DeletionTaskService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
