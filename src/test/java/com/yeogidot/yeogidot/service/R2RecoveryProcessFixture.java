package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import com.yeogidot.yeogidot.repository.UserRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** JUnit에서 생성한 임시 폴더와 파일 H2만 사용하는 별도 JVM 진입점. */
public final class R2RecoveryProcessFixture {
    private static final String FILE_URL = "https://r2-test.invalid/restart.jpg";

    public static void main(String[] args) throws Exception {
        String action = args[0];
        String scenario = args[1];
        Path directory = Path.of(args[2]).toAbsolutePath().normalize();
        System.setProperty("r2.recovery.fixture.directory", directory.toString());
        Path object = directory.resolve("fake-object.bin");
        Path callsFile = directory.resolve("delete-calls.txt");
        AtomicBoolean failDelete = new AtomicBoolean(false);

        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var photos = context.getBean(PhotoRepository.class);
            var jobs = context.getBean(R2DeletionTaskRepository.class);
            var tasks = context.getBean(R2DeletionTaskService.class);
            var worker = context.getBean(R2DeletionWorker.class);
            var clock = context.getBean(R2DeletionTestConfiguration.MutableClock.class);
            var storage = context.getBean(GcsService.class);
            doAnswer(invocation -> {
                assertThat(invocation.getArgument(0, String.class)).isEqualTo(FILE_URL);
                int calls = Integer.parseInt(Files.readString(callsFile));
                Files.writeString(callsFile, Integer.toString(calls + 1));
                if (failDelete.get()) throw new IllegalStateException("Injected storage failure");
                Files.deleteIfExists(object); // 이미 없으면 성공. 실제 네트워크 호출은 없다.
                return null;
            }).when(storage).deleteFileStrict(FILE_URL);

            if (action.equals("seed")) {
                assertThat(jobs.count()).isZero();
                Files.write(object, new byte[]{1, 2, 3});
                Files.writeString(callsFile, "0");
                var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                Photo photo = tx.execute(status -> {
                    User user = context.getBean(UserRepository.class)
                            .save(User.create("restart@example.invalid", "test-password"));
                    return photos.save(Photo.builder().user(user).filePath(FILE_URL)
                            .takenAt(LocalDateTime.of(2026, 9, 17, 12, 0)).build());
                });
                context.getBean(PhotoService.class).deletePhoto(photo.getId(), photo.getUser().getId());
                assertThat(photos.count()).isZero();
                Long id = jobs.findAll().getFirst().getId();

                switch (scenario) {
                    case "after-commit" -> { }
                    case "after-claim" -> assertThat(tasks.claim(id)).isPresent();
                    case "after-file-delete" -> {
                        assertThat(tasks.claim(id)).isPresent();
                        storage.deleteFileStrict(FILE_URL);
                    }
                    case "after-failure" -> {
                        failDelete.set(true);
                        worker.process(id);
                    }
                    default -> throw new IllegalArgumentException("Unknown test scenario");
                }
                var task = jobs.findById(id).orElseThrow();
                assertThat(task.getStatus()).isEqualTo(
                        scenario.equals("after-commit") || scenario.equals("after-failure")
                                ? R2DeletionTask.Status.PENDING : R2DeletionTask.Status.PROCESSING);
                assertThat(Files.exists(object)).isEqualTo(!scenario.equals("after-file-delete"));
                System.out.printf("BEFORE_RESTART scenario=%s, dbPhoto=0, remainingFile=%d, status=%s%n",
                        scenario, Files.exists(object) ? 1 : 0, task.getStatus());
                System.out.flush();
                // close/finally/shutdown hook 없이 종료하여 메모리 상태에 의존하지 않는지 확인한다.
                Runtime.getRuntime().halt(17);
            } else if (action.equals("recover")) {
                assertThat(jobs.count()).isEqualTo(1);
                assertThat(photos.count()).isZero();
                // 프로세스는 실제 재시작하고, 재시도/선점 만료 시각만 테스트 Clock으로 진행한다.
                clock.advance(61);
                worker.runOnce();
                var task = jobs.findAll().getFirst();
                assertThat(task.getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
                assertThat(Files.exists(object)).isFalse();
                int calls = Integer.parseInt(Files.readString(callsFile));
                assertThat(calls).isEqualTo(scenario.equals("after-file-delete")
                        || scenario.equals("after-failure") ? 2 : 1);
                assertThat(task.getAttemptCount()).isEqualTo(scenario.equals("after-commit") ? 1 : 2);
                worker.runOnce();
                assertThat(Integer.parseInt(Files.readString(callsFile))).isEqualTo(calls);
                System.out.printf("AFTER_RESTART scenario=%s, dbPhoto=0, remainingFile=0, status=COMPLETED, deleteCalls=%d%n",
                        scenario, calls);
            } else {
                throw new IllegalArgumentException("Unknown test action");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(R2DeletionTestConfiguration.class)
    static class Config {
        @Bean DataSource dataSource() {
            Path db = Path.of(System.getProperty("r2.recovery.fixture.directory")).resolve("recovery-db");
            return new DriverManagerDataSource("jdbc:h2:file:" + db.toString().replace('\\', '/')
                    + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
        }
    }
}
