package com.yeogidot.yeogidot.service.r2;

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
import com.yeogidot.yeogidot.service.R2DeletionWorker;
import com.yeogidot.yeogidot.service.TravelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(TravelDeletionRecoveryIntegrationTest.Config.class)
@TestPropertySource(properties = "app.frontend.base-url=https://frontend.invalid")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelDeletionRecoveryIntegrationTest {
    @Autowired private TravelService travelService;
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
        Fixture fixture = seedTravel();
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
        Fixture fixture = seedTravel();
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

    private Fixture seedTravel() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String key = UUID.randomUUID().toString();
            User owner = users.save(User.create(key + "@example.invalid", "test-password"));
            Travel travel = travels.save(Travel.builder().user(owner).title("Trip to remove").build());
            LocalDate date = LocalDate.of(2026, 11, 26);
            TravelDay firstDay = days.save(TravelDay.builder().travel(travel).dayNumber(1).date(date).build());
            TravelDay secondDay = days.save(TravelDay.builder().travel(travel).dayNumber(2).date(date.plusDays(1)).build());
            Photo first = seedPhoto(owner, firstDay, key + "-first");
            Photo second = seedPhoto(owner, secondDay, key + "-second");
            Photo unassigned = seedPhoto(owner, null, key + "-keep");
            return new Fixture(travel.getId(), owner, List.of(first, second), unassigned);
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
