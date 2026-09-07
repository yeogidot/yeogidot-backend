package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Comment;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.TravelLog;
import com.yeogidot.yeogidot.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:yeogidot-nplusone;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.cache.type=none"
})
class TravelDetailNPlusOneQueryCountTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TravelRepository travelRepository;

    private Statistics statistics;

    @BeforeEach
    void setUpStatistics() {
        statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void 데이터가_늘면_지연로딩_쿼리는_증가하고_분리_FetchJoin은_네_번으로_유지된다() {
        User owner = persistOwner();
        Long smallTravelId = persistTravelGraph(owner, "small", 2, 2, 2);
        Long largeTravelId = persistTravelGraph(owner, "large", 5, 3, 2);
        entityManager.flush();
        entityManager.clear();

        Comparison small = compareBothWays(smallTravelId);
        Comparison large = compareBothWays(largeTravelId);

        assertThat(small.lazySnapshot()).isEqualTo(small.fetchJoinSnapshot());
        assertThat(large.lazySnapshot()).isEqualTo(large.fetchJoinSnapshot());

        assertThat(small.fetchJoinQueryCount()).isEqualTo(4L);
        assertThat(large.fetchJoinQueryCount()).isEqualTo(4L);
        assertThat(small.lazyQueryCount()).isGreaterThan(small.fetchJoinQueryCount());
        assertThat(large.lazyQueryCount()).isGreaterThan(small.lazyQueryCount());

        System.out.printf(
                "N+1 query count - small: lazy=%d, fetchJoin=%d / large: lazy=%d, fetchJoin=%d%n",
                small.lazyQueryCount(),
                small.fetchJoinQueryCount(),
                large.lazyQueryCount(),
                large.fetchJoinQueryCount()
        );
    }

    private Comparison compareBothWays(Long travelId) {
        entityManager.clear();
        statistics.clear();
        TravelGraphSnapshot lazySnapshot = loadWithLazyRelations(travelId);
        long lazyQueryCount = statistics.getPrepareStatementCount();

        entityManager.clear();
        statistics.clear();
        TravelGraphSnapshot fetchJoinSnapshot = loadWithSeparatedFetchJoins(travelId);
        long fetchJoinQueryCount = statistics.getPrepareStatementCount();

        return new Comparison(
                lazyQueryCount,
                fetchJoinQueryCount,
                lazySnapshot,
                fetchJoinSnapshot
        );
    }

    private TravelGraphSnapshot loadWithLazyRelations(Long travelId) {
        Travel travel = travelRepository.findById(travelId).orElseThrow();
        return snapshotOf(travel);
    }

    private TravelGraphSnapshot loadWithSeparatedFetchJoins(Long travelId) {
        Travel travel = travelRepository.findByIdWithDetails(travelId).orElseThrow();
        travelRepository.findDaysWithPhotos(travelId);
        travelRepository.findPhotosWithComments(travelId);
        travelRepository.findDaysWithLogs(travelId);
        return snapshotOf(travel);
    }

    private TravelGraphSnapshot snapshotOf(Travel travel) {
        List<DaySnapshot> days = travel.getTravelDays().stream()
                .sorted(Comparator.comparing(TravelDay::getDate))
                .map(day -> new DaySnapshot(
                        day.getId(),
                        day.getPhotos().stream()
                                .map(photo -> new PhotoSnapshot(
                                        photo.getId(),
                                        photo.getComments().stream()
                                                .map(Comment::getId)
                                                .sorted()
                                                .toList()
                                ))
                                .toList(),
                        day.getTravelLogs().stream()
                                .map(TravelLog::getId)
                                .sorted()
                                .toList()
                ))
                .toList();

        return new TravelGraphSnapshot(travel.getId(), days);
    }

    private User persistOwner() {
        User owner = User.builder()
                .email("nplusone-measurement@example.com")
                .password("not-used")
                .build();
        entityManager.persist(owner);
        return owner;
    }

    private Long persistTravelGraph(
            User owner,
            String name,
            int dayCount,
            int photosPerDay,
            int commentsPerPhoto
    ) {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        Travel travel = Travel.builder()
                .user(owner)
                .title("N+1-" + name)
                .startDate(startDate)
                .endDate(startDate.plusDays(dayCount - 1L))
                .build();
        entityManager.persist(travel);

        for (int dayIndex = 0; dayIndex < dayCount; dayIndex++) {
            TravelDay day = TravelDay.builder()
                    .travel(travel)
                    .dayNumber(dayIndex + 1)
                    .date(startDate.plusDays(dayIndex))
                    .build();
            entityManager.persist(day);

            TravelLog log = TravelLog.builder()
                    .travelDay(day)
                    .content(name + "-log-" + dayIndex)
                    .build();
            entityManager.persist(log);

            for (int photoIndex = 0; photoIndex < photosPerDay; photoIndex++) {
                Photo photo = Photo.builder()
                        .user(owner)
                        .travelDay(day)
                        .filePath("https://example.test/" + name + "/" + dayIndex + "/" + photoIndex)
                        .originalName(name + "-" + dayIndex + "-" + photoIndex + ".jpg")
                        .takenAt(LocalDateTime.of(
                                startDate.plusDays(dayIndex),
                                java.time.LocalTime.of(12, photoIndex)
                        ))
                        .build();
                entityManager.persist(photo);

                for (int commentIndex = 0; commentIndex < commentsPerPhoto; commentIndex++) {
                    Comment comment = Comment.builder()
                            .photo(photo)
                            .writer(owner)
                            .content(name + "-comment-" + dayIndex + "-" + photoIndex + "-" + commentIndex)
                            .build();
                    entityManager.persist(comment);
                }
            }
        }

        return travel.getId();
    }

    private record Comparison(
            long lazyQueryCount,
            long fetchJoinQueryCount,
            TravelGraphSnapshot lazySnapshot,
            TravelGraphSnapshot fetchJoinSnapshot
    ) {
    }

    private record TravelGraphSnapshot(Long travelId, List<DaySnapshot> days) {
    }

    private record DaySnapshot(Long dayId, List<PhotoSnapshot> photos, List<Long> logIds) {
    }

    private record PhotoSnapshot(Long photoId, List<Long> commentIds) {
    }
}
