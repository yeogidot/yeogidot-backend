package com.yeogidot.yeogidot.service.r2;

import com.yeogidot.yeogidot.dto.PhotoUpdateRequest;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.GcsService;
import com.yeogidot.yeogidot.service.PhotoService;
import com.yeogidot.yeogidot.service.R2DeletionWorker;
import com.yeogidot.yeogidot.service.TravelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(TravelDeletionRecoveryIntegrationTest.Config.class)
@TestPropertySource(properties = "app.frontend.base-url=https://frontend.invalid")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelDeletionRecoveryIntegrationTest {
    @Autowired private TravelService travelService;
    @Autowired private PhotoService photoService;
    @Autowired private TravelRepository travels;
    @Autowired private TravelDayRepository days;
    @Autowired private PhotoRepository photos;
    @Autowired private UserRepository users;
    @Autowired private R2DeletionTaskRepository jobs;
    @Autowired private R2DeletionWorker worker;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private GcsService storage;

    private final Set<String> storedFiles = new HashSet<>();
    private final Set<String> failingFiles = new HashSet<>();

    @BeforeEach
    void prepareStorage() {
        jobs.deleteAll();
        clock.reset();
        storedFiles.clear();
        failingFiles.clear();
        reset(storage);
        doAnswer(call -> removeFile(call.getArgument(0))).when(storage).deleteFile(anyString());
        doAnswer(call -> removeFile(call.getArgument(0))).when(storage).deleteFileStrict(anyString());
        doAnswer(call -> removeFile(Photo.mapThumbnailObjectUrl(call.getArgument(0, String.class))))
                .when(storage).deleteMapThumbnailFor(anyString());
    }

    @Test
    void storesOneTaskPerPhotoAndRetriesTransientStorageFailure() {
        Fixture fixture = seedTravel(2);
        Photo failing = fixture.photos().getFirst();
        Photo successful = fixture.photos().getLast();
        failingFiles.add(failing.getFilePath());

        travelService.deleteTravel(fixture.travelId(), fixture.owner());

        assertThat(travels.existsById(fixture.travelId())).isFalse();
        assertThat(photos.findAllById(fixture.photos().stream().map(Photo::getId).toList())).isEmpty();
        assertThat(photos.existsById(fixture.unassignedPhoto().getId())).isTrue();
        assertThat(storedFiles).containsAll(failing.storageFileUrls());
        assertThat(jobs.findAll()).hasSize(2)
                .extracting(R2DeletionTask::getFileUrl)
                .containsExactlyInAnyOrder(failing.getFilePath(), successful.getFilePath());
        verifyNoInteractions(storage);

        worker.runOnce();

        assertThat(jobFor(failing).getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(jobFor(failing).getAttemptCount()).isEqualTo(1);
        assertThat(jobFor(failing).getAvailableAt()).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jobFor(successful).getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        var remainingFiles = new HashSet<>(failing.storageFileUrls());
        remainingFiles.addAll(fixture.unassignedPhoto().storageFileUrls());
        assertThat(storedFiles).containsExactlyInAnyOrderElementsOf(remainingFiles);

        failingFiles.clear();
        clock.advance(1);
        worker.runOnce();

        assertThat(jobFor(failing).getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(jobFor(failing).getAttemptCount()).isEqualTo(2);
        assertThat(storedFiles).containsExactlyInAnyOrderElementsOf(fixture.unassignedPhoto().storageFileUrls());
    }

    @Test
    void rollbackKeepsTravelPhotosAndFilesAndDiscardsJobs() {
        Fixture fixture = seedTravel(2);
        Set<String> previousFiles = Set.copyOf(storedFiles);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            travelService.deleteTravel(fixture.travelId(), fixture.owner());
            assertThat(jobs.count()).isEqualTo(2);
            status.setRollbackOnly();
        });

        assertThat(travels.existsById(fixture.travelId())).isTrue();
        assertThat(photos.findAllById(fixture.photos().stream().map(Photo::getId).toList())).hasSize(2);
        assertThat(jobs.count()).isZero();
        worker.runOnce();
        verifyNoInteractions(storage);
        assertThat(storedFiles).isEqualTo(previousFiles);
    }

    @Test
    void deletingMiddleDayQueuesOnlyItsPhotoAndRetriesThumbnailDeletion() {
        Fixture fixture = seedTravel(3);
        Photo first = fixture.photos().getFirst();
        Photo deleted = fixture.photos().get(1);
        Photo last = fixture.photos().getLast();
        Long dayId = deleted.getTravelDay().getId();
        Set<String> previousFiles = Set.copyOf(storedFiles);
        String thumbnail = deleted.mapThumbnailObjectUrl();
        failingFiles.add(thumbnail);

        travelService.deleteTravelDay(dayId, fixture.owner());

        assertThat(days.existsById(dayId)).isFalse();
        assertThat(photos.existsById(deleted.getId())).isFalse();
        assertThat(photos.findAllById(List.of(first.getId(), last.getId(), fixture.unassignedPhoto().getId())))
                .hasSize(3);
        assertThat(days.findById(first.getTravelDay().getId()).orElseThrow().getDayNumber()).isEqualTo(1);
        assertThat(days.findById(last.getTravelDay().getId()).orElseThrow().getDayNumber()).isEqualTo(2);
        Travel remainingTravel = travels.findById(fixture.travelId()).orElseThrow();
        assertThat(remainingTravel.getStartDate()).isEqualTo(first.getTravelDay().getDate());
        assertThat(remainingTravel.getEndDate()).isEqualTo(last.getTravelDay().getDate());
        assertThat(jobs.findAll()).extracting(R2DeletionTask::getFileUrl)
                .containsExactly(deleted.getFilePath());
        verifyNoInteractions(storage);
        assertThat(storedFiles).isEqualTo(previousFiles);

        worker.runOnce();

        assertThat(jobFor(deleted).getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
        assertThat(jobFor(deleted).getAttemptCount()).isEqualTo(1);
        assertThat(storedFiles).contains(thumbnail).doesNotContain(deleted.getFilePath());

        failingFiles.clear();
        clock.advance(1);
        worker.runOnce();

        assertThat(jobFor(deleted).getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(jobFor(deleted).getAttemptCount()).isEqualTo(2);
        Set<String> remainingFiles = new HashSet<>(previousFiles);
        remainingFiles.removeAll(deleted.storageFileUrls());
        assertThat(storedFiles).isEqualTo(remainingFiles);
    }

    @Test
    void rollbackKeepsDeletedDayPhotoAndNumberingAndDiscardsJob() {
        Fixture fixture = seedTravel(3);
        Photo deleted = fixture.photos().get(1);
        Long dayId = deleted.getTravelDay().getId();
        Long lastDayId = fixture.photos().getLast().getTravelDay().getId();
        Set<String> previousFiles = Set.copyOf(storedFiles);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            travelService.deleteTravelDay(dayId, fixture.owner());
            assertThat(jobs.count()).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(days.findById(dayId).orElseThrow().getDayNumber()).isEqualTo(2);
        assertThat(days.findById(lastDayId).orElseThrow().getDayNumber()).isEqualTo(3);
        assertThat(photos.existsById(deleted.getId())).isTrue();
        assertThat(jobs.count()).isZero();
        worker.runOnce();
        verifyNoInteractions(storage);
        assertThat(storedFiles).isEqualTo(previousFiles);
    }

    @ParameterizedTest
    @EnumSource(MoveRoute.class)
    void movingFirstKeepsPhotoAndQueuesNoDeletion(MoveRoute route) throws Exception {
        Fixture fixture = seedTravel(3);
        Photo photo = fixture.photos().get(1);
        Long sourceId = photo.getTravelDay().getId();
        Long targetId = fixture.photos().getLast().getTravelDay().getId();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var transaction = new TransactionTemplate(transactionManager);
            Future<?> deletion = transaction.execute(status -> {
                movePhoto(route, fixture.owner(), photo.getId(), targetId);
                var started = new CountDownLatch(1);
                Future<?> pending = executor.submit(() -> {
                    started.countDown();
                    travelService.deleteTravelDay(sourceId, fixture.owner());
                });
                assertWaiting(started, pending);
                return pending;
            });
            deletion.get(10, TimeUnit.SECONDS);
        }

        assertThat(days.existsById(sourceId)).isFalse();
        assertThat(photos.findById(photo.getId()).orElseThrow().getTravelDay().getId()).isEqualTo(targetId);
        assertThat(days.findById(targetId).orElseThrow().getDayNumber()).isEqualTo(2);
        assertThat(jobs.count()).isZero();
        worker.runOnce();
        verifyNoInteractions(storage);
        assertThat(storedFiles).containsAll(photo.storageFileUrls());
    }

    @ParameterizedTest
    @EnumSource(MoveRoute.class)
    void deletingFirstRejectsMoveAndQueuesOnlyDeletedPhoto(MoveRoute route) throws Exception {
        Fixture fixture = seedTravel(3);
        Photo photo = fixture.photos().get(1);
        Long sourceId = photo.getTravelDay().getId();
        Long targetId = fixture.photos().getLast().getTravelDay().getId();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var transaction = new TransactionTemplate(transactionManager);
            Future<?> move = transaction.execute(status -> {
                travelService.deleteTravelDay(sourceId, fixture.owner());
                var started = new CountDownLatch(1);
                Future<?> pending = executor.submit(() -> {
                    started.countDown();
                    movePhoto(route, fixture.owner(), photo.getId(), targetId);
                });
                assertWaiting(started, pending);
                return pending;
            });
            assertThatThrownBy(() -> move.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("사진");
        }

        assertThat(photos.existsById(photo.getId())).isFalse();
        assertThat(days.existsById(targetId)).isTrue();
        assertThat(jobs.findAll()).extracting(R2DeletionTask::getFileUrl).containsExactly(photo.getFilePath());
    }

    private void movePhoto(MoveRoute route, User owner, Long photoId, Long targetId) {
        switch (route) {
            case MOVE_API -> photoService.movePhotoToDay(photoId, targetId, owner.getId());
            case UPDATE_API -> {
                PhotoUpdateRequest request = new PhotoUpdateRequest();
                request.setDayId(targetId);
                photoService.updatePhoto(photoId, request, owner);
            }
            case ADD_TO_DAY_API -> travelService.addPhotosToDay(targetId, List.of(photoId), owner);
        }
    }

    private void assertWaiting(CountDownLatch started, Future<?> pending) {
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for the competing request", exception);
        }
    }

    private enum MoveRoute { MOVE_API, UPDATE_API, ADD_TO_DAY_API }

    private Void removeFile(String url) {
        if (failingFiles.contains(url)) {
            throw new IllegalStateException("Injected storage deletion failure");
        }
        storedFiles.remove(url);
        return null;
    }

    private R2DeletionTask jobFor(Photo photo) {
        return jobs.findAll().stream().filter(job -> job.getFileUrl().equals(photo.getFilePath()))
                .findFirst().orElseThrow();
    }

    private Fixture seedTravel(int numberOfDays) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String key = UUID.randomUUID().toString();
            User owner = users.save(User.create(key + "@example.invalid", "test-password"));
            LocalDate startDate = LocalDate.of(2026, 11, 26);
            Travel travel = travels.save(Travel.builder().user(owner).title("Trip to remove")
                    .startDate(startDate).endDate(startDate.plusDays(numberOfDays - 1)).build());
            List<Photo> dayPhotos = new ArrayList<>();
            for (int dayNumber = 1; dayNumber <= numberOfDays; dayNumber++) {
                TravelDay day = days.save(TravelDay.builder().travel(travel).dayNumber(dayNumber)
                        .date(startDate.plusDays(dayNumber - 1)).build());
                dayPhotos.add(seedPhoto(owner, day, key + "-day-" + dayNumber));
            }
            Photo unassigned = seedPhoto(owner, null, key + "-keep");
            return new Fixture(travel.getId(), owner, dayPhotos, unassigned);
        });
    }

    private Photo seedPhoto(User owner, TravelDay day, String key) {
        Photo photo = photos.save(Photo.builder().user(owner).travelDay(day)
                .filePath("https://r2-test.invalid/" + key + ".jpg").build());
        storedFiles.addAll(photo.storageFileUrls());
        return photo;
    }

    private record Fixture(Long travelId, User owner, List<Photo> photos, Photo unassignedPhoto) { }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({R2DeletionTestConfiguration.class, TravelService.class})
    static class Config {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
        }
    }
}
