package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.config.R2DeletionSettings;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 삭제 작업자의 배치 경계와 부분 실패를 검증한다. 부하/성능 측정은 아니다.
 * 메모리 H2에 작업만 생성하고 가짜 파일 Map을 사용한다. 사진 API/실제 R2는 호출하지 않는다.
 * 스케줄러와 application*.properties는 로드하지 않고 작업자와 테스트 Clock을 직접 진행한다.
 */
@SpringJUnitConfig(R2DeletionBatchIntegrationTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2DeletionBatchIntegrationTest {
    @Autowired private R2DeletionTaskService tasks;
    @Autowired private R2DeletionWorker worker;
    @Autowired private R2DeletionTaskRepository repository;
    @Autowired private GcsService storage;
    @Autowired private R2DeletionSettings settings;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private final Map<String, byte[]> files = new HashMap<>();
    private final Set<String> failingUrls = new HashSet<>();

    @BeforeEach
    void prepareIsolatedStorage() throws Exception {
        verifyTestDatabase(dataSource);
        assertThat(settings.batchSize()).isEqualTo(20);
        repository.deleteAll();
        clock.reset();
        files.clear();
        failingUrls.clear();
        reset(storage);
        doAnswer(invocation -> {
            String url = invocation.getArgument(0);
            // 중복 호출 또는 엉뚱한 파일 삭제를 성공으로 숨기지 않는다.
            assertThat(files).containsKey(url);
            if (failingUrls.contains(url)) {
                throw new IllegalStateException("Injected per-file deletion failure");
            }
            files.remove(url);
            return null;
        }).when(storage).deleteFileStrict(anyString());
    }

    protected void verifyTestDatabase(DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

    @Test
    void 작업_45개를_20개씩_처리하고_완료된_작업은_다시_호출하지_않는다() {
        List<String> urls = enqueueTasks(45);
        assertBatchProgress(urls, 0);
        verifyNoInteractions(storage);

        for (int completed : new int[]{20, 40, 45}) {
            worker.runOnce();
            assertBatchProgress(urls, completed);
            verify(storage, times(completed)).deleteFileStrict(anyString());
            System.out.printf("R2 batch: total=45, completed=%d, pending=%d, remainingFiles=%d%n",
                    completed, 45 - completed, files.size());
        }

        worker.runOnce();
        assertBatchProgress(urls, 45);
        assertThat(tasks.readyIds()).isEmpty();
        for (String url : urls) {
            verify(storage, times(1)).deleteFileStrict(url);
        }
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 열개_중_세개가_실패해도_나머지를_처리하고_실패한_세개만_재시도한다() {
        List<String> urls = enqueueTasks(10);
        // 첫 작업도 실패시켜 하나의 실패가 뒤의 작업을 막지 않는지 확인한다.
        Set<String> failed = Set.of(urls.get(0), urls.get(4), urls.get(8));
        failingUrls.addAll(failed);
        worker.runOnce();

        Map<String, R2DeletionTask> first = tasksByUrl(urls);
        for (String url : urls) {
            R2DeletionTask task = first.get(url);
            assertThat(task.getStatus()).isEqualTo(failed.contains(url)
                    ? R2DeletionTask.Status.PENDING : R2DeletionTask.Status.COMPLETED);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            if (failed.contains(url)) {
                assertThat(task.getAvailableAt()).isEqualTo(clock.instant().plusSeconds(settings.initialRetrySeconds()));
                assertThat(task.getLastError()).isEqualTo("IllegalStateException");
            }
        }
        assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(failed);
        verify(storage, times(10)).deleteFileStrict(anyString());
        System.out.println("R2 partial failure: total=10, completed=7, retryPending=3, remainingFiles=3");

        failingUrls.clear();
        worker.runOnce(); // 실패 원인이 없어져도 예약 시각 전에는 재호출하지 않는다.
        assertThat(tasks.readyIds()).isEmpty();
        verify(storage, times(10)).deleteFileStrict(anyString());
        assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(failed);

        clock.advance(settings.initialRetrySeconds());
        assertThat(tasks.readyIds()).containsExactlyInAnyOrderElementsOf(
                failed.stream().map(url -> first.get(url).getId()).toList());
        worker.runOnce();

        Map<String, R2DeletionTask> completed = tasksByUrl(urls);
        for (String url : urls) {
            int expectedAttempts = failed.contains(url) ? 2 : 1;
            assertThat(completed.get(url).getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
            assertThat(completed.get(url).getAttemptCount()).isEqualTo(expectedAttempts);
            assertThat(completed.get(url).getLastError()).isNull();
            verify(storage, times(expectedAttempts)).deleteFileStrict(url);
        }
        assertThat(files).isEmpty();
        assertThat(tasks.readyIds()).isEmpty();
        worker.runOnce();
        verifyNoMoreInteractions(storage);
        System.out.println("R2 partial recovery: completed=10, remainingFiles=0, deleteCalls=13 (7x1 + 3x2)");
    }

    private List<String> enqueueTasks(int count) {
        String prefix = "https://r2-test.invalid/" + UUID.randomUUID() + "/";
        List<String> urls = IntStream.range(0, count).mapToObj(i -> prefix + i + ".jpg").toList();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (String url : urls) {
                tasks.enqueue(url);
            }
        });
        urls.forEach(url -> files.put(url, new byte[]{1, 2, 3}));
        return urls;
    }

    private Map<String, R2DeletionTask> tasksByUrl(List<String> expectedUrls) {
        List<R2DeletionTask> saved = repository.findAll();
        assertThat(saved).hasSize(expectedUrls.size());
        Map<String, R2DeletionTask> byUrl = saved.stream()
                .collect(Collectors.toMap(R2DeletionTask::getFileUrl, Function.identity()));
        assertThat(byUrl.keySet()).containsExactlyInAnyOrderElementsOf(expectedUrls);
        return byUrl;
    }

    private void assertBatchProgress(List<String> urls, int completed) {
        Map<String, R2DeletionTask> saved = tasksByUrl(urls);
        // 동일 예약 시각의 작업은 ID 순으로 처리된다. 미처리 작업은 시도 횟수도 0이다.
        for (int i = 0; i < urls.size(); i++) {
            R2DeletionTask task = saved.get(urls.get(i));
            assertThat(task.getStatus()).isEqualTo(i < completed
                    ? R2DeletionTask.Status.COMPLETED : R2DeletionTask.Status.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(i < completed ? 1 : 0);
        }
        assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(urls.subList(completed, urls.size()));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(R2DeletionTestConfiguration.class)
    static class TestConfig {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
        }
    }
}
