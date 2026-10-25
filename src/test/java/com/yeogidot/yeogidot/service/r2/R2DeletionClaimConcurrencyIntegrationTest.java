package com.yeogidot.yeogidot.service.r2;

import com.yeogidot.yeogidot.config.R2DeletionSettings;
import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import com.yeogidot.yeogidot.service.GcsService;
import com.yeogidot.yeogidot.service.R2DeletionTaskService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.Advised;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Two threads compete at the real repository lock boundary, inside separate claim transactions.
 * Test advice only synchronizes entry: it does not replace SQL, locking, or the returned entity.
 * Fixed Clock keeps leases valid throughout the race. No scheduler, HTTP, or real R2 is used.
 */
@SpringJUnitConfig(R2DeletionBatchIntegrationTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class R2DeletionClaimConcurrencyIntegrationTest {
    @Autowired private R2DeletionTaskService tasks;
    @Autowired private R2DeletionTaskRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private R2DeletionSettings settings;
    @Autowired private R2DeletionTestConfiguration.MutableClock clock;
    @Autowired private GcsService storage;

    private final Set<Long> connectionIds = ConcurrentHashMap.newKeySet();
    private boolean mysql;
    private MethodInterceptor raceAdvice;

    @BeforeEach
    void prepare() throws Exception {
        verifyTestDatabase(dataSource); // Check before any deletion, including in the MySQL subclass.
        stopSynchronizing();
        reset(storage);
        repository.deleteAll();
        clock.reset();
        connectionIds.clear();
        try (var connection = dataSource.getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
        }
    }

    protected void verifyTestDatabase(DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

    @RepeatedTest(10)
    void simultaneousFirstClaimHasExactlyOneOwner() throws Exception {
        Long id = enqueue(1).getFirst();
        synchronizeBeforeRealLock();
        var results = compete(() -> tasks.claim(id).stream().toList());
        var claims = results.stream().flatMap(List::stream).toList();

        assertThat(results.stream().mapToInt(List::size).sorted().toArray()).containsExactly(0, 1);
        assertThat(claims).hasSize(1);
        assertOwnedOnce(claims.getFirst());
        assertDistinctMySqlConnections(2);
        // Disable the race hook before sequential checks; the real repository remains in use.
        stopSynchronizing();
        assertThat(tasks.claim(id)).isEmpty();
        assertThat(tasks.readyIds()).isEmpty();
        tasks.complete(claims.getFirst());
        var completed = repository.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(completed.getLeaseToken()).isNull();
        verifyNoInteractions(storage);
        System.out.println("R2 claim race: tasks=1, contenders=2, owners=1, attempts=1");
    }

    @RepeatedTest(10)
    void overlappingReadySnapshotsHaveNoDuplicateClaimsAndKeepRemainder() throws Exception {
        assertThat(settings.batchSize()).isEqualTo(20);
        List<Long> ids = enqueue(25);
        var snapshotsReady = new CyclicBarrier(2);
        synchronizeBeforeRealLock();
        var results = compete(() -> {
            // Both workers obtain the same committed candidate list before either starts claiming.
            List<Long> snapshot = tasks.readyIds();
            assertThat(snapshot).containsExactlyElementsOf(ids.subList(0, 20));
            snapshotsReady.await(10, TimeUnit.SECONDS);
            List<R2DeletionTaskService.Claim> owned = new ArrayList<>();
            for (Long id : snapshot) {
                tasks.claim(id).ifPresent(owned::add);
            }
            return owned;
        });
        var firstIds = results.get(0).stream().map(R2DeletionTaskService.Claim::id).toList();
        var secondIds = results.get(1).stream().map(R2DeletionTaskService.Claim::id).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        var claims = Stream.concat(results.get(0).stream(), results.get(1).stream()).toList();
        assertThat(claims).hasSize(20);
        assertThat(claims.stream().map(R2DeletionTaskService.Claim::id).toList())
                .containsExactlyInAnyOrderElementsOf(ids.subList(0, 20));
        assertThat(claims.stream().map(R2DeletionTaskService.Claim::token).toList()).doesNotHaveDuplicates();
        claims.forEach(this::assertOwnedOnce);
        // DriverManagerDataSource creates one physical connection for every REQUIRES_NEW claim.
        assertDistinctMySqlConnections(40);
        stopSynchronizing();
        assertThat(tasks.readyIds()).containsExactlyElementsOf(ids.subList(20, 25));
        for (Long id : ids.subList(20, 25)) {
            var pending = repository.findById(id).orElseThrow();
            assertThat(pending.getStatus()).isEqualTo(R2DeletionTask.Status.PENDING);
            assertThat(pending.getAttemptCount()).isZero();
            assertThat(pending.getLeaseToken()).isNull();
        }
        for (var claim : claims) {
            assertThat(tasks.claim(claim.id())).isEmpty();
            tasks.complete(claim);
        }
        // Unselected rows are not lost: the next cycle can claim and complete them.
        for (Long id : tasks.readyIds()) {
            tasks.complete(tasks.claim(id).orElseThrow());
        }
        assertThat(repository.findAll()).hasSize(25).allSatisfy(task -> {
            assertThat(task.getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLeaseToken()).isNull();
        });
        assertThat(tasks.readyIds()).isEmpty();
        verifyNoInteractions(storage);
        System.out.println("R2 batch claim race: candidatesPerWorker=20, contenders=2, uniqueClaims=20, "
                + "overlap=0, pending=5, completedAfterNextCycle=25");
    }

    private void synchronizeBeforeRealLock() {
        var beforeLock = new CyclicBarrier(2);
        raceAdvice = invocation -> {
            if (!invocation.getMethod().getName().equals("findLockedById")) return invocation.proceed();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (mysql) {
                // JdbcTemplate joins the current JPA transaction's bound connection.
                connectionIds.add(new JdbcTemplate(dataSource).queryForObject("SELECT CONNECTION_ID()", Long.class));
            }
            // Never wait at the barrier after obtaining the row lock: that would deadlock the test.
            beforeLock.await(10, TimeUnit.SECONDS);
            return invocation.proceed();
        };
        ((Advised) repository).addAdvice(0, raceAdvice);
    }

    @AfterEach
    void stopSynchronizing() {
        if (raceAdvice != null) {
            ((Advised) repository).removeAdvice(raceAdvice);
            raceAdvice = null;
        }
    }

    private void assertDistinctMySqlConnections(int expected) {
        if (mysql) assertThat(connectionIds).hasSize(expected);
    }

    private List<Long> enqueue(int count) {
        return new TransactionTemplate(transactionManager).execute(status ->
                IntStream.range(0, count).mapToObj(i -> tasks.enqueue(
                        "https://r2-test.invalid/claim-race/" + UUID.randomUUID() + ".jpg")).toList());
    }

    private void assertOwnedOnce(R2DeletionTaskService.Claim claim) {
        var saved = repository.findById(claim.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(R2DeletionTask.Status.PROCESSING);
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getLeaseToken()).isEqualTo(claim.token());
        assertThat(saved.getAvailableAt()).isEqualTo(clock.instant().plusSeconds(settings.leaseSeconds()));
    }

    private <T> List<T> compete(Callable<T> action) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(action);
        var second = executor.submit(action);
        try {
            return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        } finally {
            first.cancel(true);
            second.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
