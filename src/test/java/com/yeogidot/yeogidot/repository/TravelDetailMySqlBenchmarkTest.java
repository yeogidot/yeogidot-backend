package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.dto.TravelDto;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL에서 지연 로딩과 분리 Fetch Join의 조회 + DTO 변환 시간을 비교한다.
 *
 * <p>RUN_MYSQL_BENCHMARK=true일 때만 실행하며, application-benchmark.properties에
 * 고정된 전용 yeogidot_benchmark 스키마만 사용한다.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("benchmark")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_BENCHMARK", matches = "(?i)true")
class TravelDetailMySqlBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 10;
    private static final int MEASURED_ITERATIONS = 50;
    private static final int DAY_COUNT = 10;
    private static final int PHOTOS_PER_DAY = 20;
    private static final int COMMENTS_PER_PHOTO = 3;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TravelRepository travelRepository;

    private Statistics statistics;

    @BeforeEach
    void setUpStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void MySQL에서_지연로딩과_분리_FetchJoin의_처리시간을_반복_측정한다() throws IOException {
        User owner = persistOwner();
        Long travelId = persistTravelGraph(owner);
        entityManager.flush();
        entityManager.clear();

        Measurement lazyVerification = measureOnce(Strategy.LAZY, travelId);
        Measurement fetchVerification = measureOnce(Strategy.FETCH_JOIN, travelId);

        assertThat(fetchVerification.response())
                .usingRecursiveComparison()
                .isEqualTo(lazyVerification.response());
        assertThat(fetchVerification.queryCount()).isEqualTo(4L);
        assertThat(lazyVerification.queryCount()).isGreaterThan(fetchVerification.queryCount());

        for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) {
            if (iteration % 2 == 0) {
                measureOnce(Strategy.LAZY, travelId);
                measureOnce(Strategy.FETCH_JOIN, travelId);
            } else {
                measureOnce(Strategy.FETCH_JOIN, travelId);
                measureOnce(Strategy.LAZY, travelId);
            }
        }

        List<Measurement> lazyMeasurements = new ArrayList<>();
        List<Measurement> fetchMeasurements = new ArrayList<>();

        for (int iteration = 0; iteration < MEASURED_ITERATIONS; iteration++) {
            if (iteration % 2 == 0) {
                lazyMeasurements.add(measureOnce(Strategy.LAZY, travelId));
                fetchMeasurements.add(measureOnce(Strategy.FETCH_JOIN, travelId));
            } else {
                fetchMeasurements.add(measureOnce(Strategy.FETCH_JOIN, travelId));
                lazyMeasurements.add(measureOnce(Strategy.LAZY, travelId));
            }
        }

        Summary lazySummary = summarize(lazyMeasurements);
        Summary fetchSummary = summarize(fetchMeasurements);
        double medianImprovementPercent = improvementPercent(
                lazySummary.medianMillis(),
                fetchSummary.medianMillis()
        );

        writeCsv(lazyMeasurements, fetchMeasurements);

        System.out.printf(Locale.US,
                "MySQL travel detail benchmark - data: days=%d, photos=%d, comments=%d, logs=%d%n",
                DAY_COUNT,
                DAY_COUNT * PHOTOS_PER_DAY,
                DAY_COUNT * PHOTOS_PER_DAY * COMMENTS_PER_PHOTO,
                DAY_COUNT
        );
        System.out.printf(Locale.US,
                "lazy: queries=%d, median=%.3fms, p95=%.3fms%n",
                lazySummary.queryCount(), lazySummary.medianMillis(), lazySummary.p95Millis()
        );
        System.out.printf(Locale.US,
                "fetchJoin: queries=%d, median=%.3fms, p95=%.3fms%n",
                fetchSummary.queryCount(), fetchSummary.medianMillis(), fetchSummary.p95Millis()
        );
        System.out.printf(Locale.US,
                "median improvement: %.2f%%%n",
                medianImprovementPercent
        );
        System.out.println("raw samples: build/benchmark-results/travel-detail-mysql.csv");
    }

    private Measurement measureOnce(Strategy strategy, Long travelId) {
        entityManager.clear();
        statistics.clear();

        long startedAt = System.nanoTime();
        TravelDto.DetailResponse response = switch (strategy) {
            case LAZY -> loadLazily(travelId);
            case FETCH_JOIN -> loadWithSeparatedFetchJoins(travelId);
        };
        long elapsedNanos = System.nanoTime() - startedAt;

        return new Measurement(
                strategy,
                elapsedNanos / 1_000_000.0,
                statistics.getPrepareStatementCount(),
                response
        );
    }

    private TravelDto.DetailResponse loadLazily(Long travelId) {
        Travel travel = travelRepository.findById(travelId).orElseThrow();
        return toDetailResponse(travel);
    }

    private TravelDto.DetailResponse loadWithSeparatedFetchJoins(Long travelId) {
        Travel travel = travelRepository.findByIdWithDetails(travelId).orElseThrow();
        travelRepository.findDaysWithPhotos(travelId);
        travelRepository.findPhotosWithComments(travelId);
        travelRepository.findDaysWithLogs(travelId);
        return toDetailResponse(travel);
    }

    private TravelDto.DetailResponse toDetailResponse(Travel travel) {
        List<TravelDto.TravelDayDetail> days = travel.getTravelDays().stream()
                .sorted(Comparator.comparing(TravelDay::getDate))
                .map(this::toDayDetail)
                .toList();

        return TravelDto.DetailResponse.builder()
                .travelId(travel.getId())
                .title(travel.getTitle())
                .trvRegion(travel.getTrvRegion())
                .representativePhotoId(travel.getRepresentativePhotoId())
                .shareUrl(travel.getShareUrl())
                .startDate(travel.getStartDate())
                .endDate(travel.getEndDate())
                .days(days)
                .build();
    }

    private TravelDto.TravelDayDetail toDayDetail(TravelDay day) {
        List<TravelDto.PhotoDetail> photos = day.getPhotos().stream()
                .map(photo -> {
                    List<TravelDto.CommentDetail> comments = photo.getComments().stream()
                            .map(comment -> TravelDto.CommentDetail.builder()
                                    .commentId(comment.getId())
                                    .content(comment.getContent())
                                    .createdAt(comment.getCreatedDate())
                                    .build())
                            .toList();

                    return TravelDto.PhotoDetail.builder()
                            .photoId(photo.getId())
                            .url(photo.getFilePath())
                            .takenAt(photo.getTakenAt())
                            .latitude(photo.getLatitude())
                            .longitude(photo.getLongitude())
                            .region(photo.getRegion())
                            .comments(comments)
                            .build();
                })
                .toList();

        TravelDto.DiaryDetail diary = day.getTravelLogs().stream()
                .findFirst()
                .map(log -> TravelDto.DiaryDetail.builder()
                        .logId(log.getId())
                        .content(log.getContent())
                        .logCreated(log.getCreatedDate())
                        .build())
                .orElse(null);

        return TravelDto.TravelDayDetail.builder()
                .dayId(day.getId())
                .dayNumber(day.getDayNumber())
                .date(day.getDate())
                .dayRegion(day.getDayRegion())
                .photos(photos)
                .diary(diary)
                .build();
    }

    private Summary summarize(List<Measurement> measurements) {
        List<Double> sortedMillis = measurements.stream()
                .map(Measurement::elapsedMillis)
                .sorted()
                .toList();

        int medianIndex = sortedMillis.size() / 2;
        double median = sortedMillis.size() % 2 == 0
                ? (sortedMillis.get(medianIndex - 1) + sortedMillis.get(medianIndex)) / 2.0
                : sortedMillis.get(medianIndex);
        int p95Index = Math.max(0, (int) Math.ceil(sortedMillis.size() * 0.95) - 1);
        long queryCount = measurements.getFirst().queryCount();

        assertThat(measurements)
                .extracting(Measurement::queryCount)
                .containsOnly(queryCount);

        return new Summary(queryCount, median, sortedMillis.get(p95Index));
    }

    private double improvementPercent(double beforeMillis, double afterMillis) {
        return (beforeMillis - afterMillis) / beforeMillis * 100.0;
    }

    private void writeCsv(
            List<Measurement> lazyMeasurements,
            List<Measurement> fetchMeasurements
    ) throws IOException {
        Path output = Path.of("build", "benchmark-results", "travel-detail-mysql.csv");
        Files.createDirectories(output.getParent());

        List<String> rows = new ArrayList<>();
        rows.add("strategy,iteration,elapsed_ms,query_count");
        appendRows(rows, lazyMeasurements);
        appendRows(rows, fetchMeasurements);
        Files.write(output, rows, StandardCharsets.UTF_8);
    }

    private void appendRows(List<String> rows, List<Measurement> measurements) {
        for (int index = 0; index < measurements.size(); index++) {
            Measurement measurement = measurements.get(index);
            rows.add(String.format(
                    Locale.US,
                    "%s,%d,%.3f,%d",
                    measurement.strategy().csvName,
                    index + 1,
                    measurement.elapsedMillis(),
                    measurement.queryCount()
            ));
        }
    }

    private User persistOwner() {
        User owner = User.builder()
                .email("mysql-benchmark@example.com")
                .password("not-used")
                .build();
        entityManager.persist(owner);
        return owner;
    }

    private Long persistTravelGraph(User owner) {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        Travel travel = Travel.builder()
                .user(owner)
                .title("MySQL benchmark")
                .trvRegion("benchmark-region")
                .startDate(startDate)
                .endDate(startDate.plusDays(DAY_COUNT - 1L))
                .build();
        entityManager.persist(travel);

        for (int dayIndex = 0; dayIndex < DAY_COUNT; dayIndex++) {
            TravelDay day = TravelDay.builder()
                    .travel(travel)
                    .dayNumber(dayIndex + 1)
                    .dayRegion("benchmark-day-region")
                    .date(startDate.plusDays(dayIndex))
                    .build();
            entityManager.persist(day);

            entityManager.persist(TravelLog.builder()
                    .travelDay(day)
                    .content("benchmark-log-" + dayIndex)
                    .build());

            for (int photoIndex = 0; photoIndex < PHOTOS_PER_DAY; photoIndex++) {
                Photo photo = Photo.builder()
                        .user(owner)
                        .travelDay(day)
                        .filePath("https://example.test/mysql/" + dayIndex + "/" + photoIndex)
                        .originalName(dayIndex + "-" + photoIndex + ".jpg")
                        .takenAt(LocalDateTime.of(
                                startDate.plusDays(dayIndex),
                                LocalTime.of(12, photoIndex)
                        ))
                        .latitude(BigDecimal.valueOf(37.5665))
                        .longitude(BigDecimal.valueOf(126.9780))
                        .region("benchmark-region")
                        .build();
                entityManager.persist(photo);

                for (int commentIndex = 0; commentIndex < COMMENTS_PER_PHOTO; commentIndex++) {
                    entityManager.persist(Comment.builder()
                            .photo(photo)
                            .writer(owner)
                            .content("benchmark-comment-" + dayIndex + "-" + photoIndex + "-" + commentIndex)
                            .build());
                }
            }
        }

        return travel.getId();
    }

    private enum Strategy {
        LAZY("lazy"),
        FETCH_JOIN("fetch_join");

        private final String csvName;

        Strategy(String csvName) {
            this.csvName = csvName;
        }
    }

    private record Measurement(
            Strategy strategy,
            double elapsedMillis,
            long queryCount,
            TravelDto.DetailResponse response
    ) {
    }

    private record Summary(long queryCount, double medianMillis, double p95Millis) {
    }
}
