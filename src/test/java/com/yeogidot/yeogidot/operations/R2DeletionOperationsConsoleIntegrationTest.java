package com.yeogidot.yeogidot.operations;

import com.yeogidot.yeogidot.service.R2DeletionOperationsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 메뉴 → 기존 CLI → 실제 운영 서비스의 트랜잭션을 H2에서 확인한다. 외부 접속 없음. */
class R2DeletionOperationsConsoleIntegrationTest {
    @TempDir Path directory;
    private EmbeddedDatabase source;
    private JdbcTemplate jdbc;
    private R2DeletionOperationsService operations;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach void prepare() {
        source = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("menu_" + UUID.randomUUID() + ";MODE=MySQL").build();
        new ResourceDatabasePopulator(new ClassPathResource("db/manual/20260917_create_r2_deletion_task.sql"),
                new ClassPathResource("db/manual/20261009_create_r2_deletion_retry_audit.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO r2_deletion_task (id,file_url,status,attempt_count,available_at,last_error,created_at,updated_at) "
                + "VALUES (7,'https://r2-test.invalid/fixture','FAILED',3,CURRENT_TIMESTAMP,'InjectedFailure',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        operations = new R2DeletionOperationsService(source, Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach void close() { if (source != null) source.shutdown(); }

    private void run(R2DeletionOperationsService service, String... lines) throws Exception {
        var input = new ArrayDeque<>(List.of(lines));
        var settings = R2DeletionOperationsCli.settings(Map.of("R2_OPS_DB_NAME", "yeogidot_r2_test",
                "R2_OPS_DB_USERNAME", "test-operator", "R2_OPS_DB_PASSWORD", "never-connect-mysql"));
        assertThat(R2DeletionOperationsConsole.run(settings, directory.resolve(".r2-ops"),
                new R2DeletionOperationsConsole.Input() {
                    public String read(String prompt) throws Exception {
                        if (input.isEmpty()) throw new EOFException();
                        return input.removeFirst();
                    }
                    public char[] password(String prompt) { throw new AssertionError("No password input"); }
                }, new PrintStream(output, true, StandardCharsets.UTF_8), ignored -> service)).isZero();
    }

    @Test void 메뉴_승인으로_FAILED만_PENDING으로_예약하고_감사이력을_남긴다() throws Exception {
        run(operations, "1", "2", "", "4", "7", "테스트 오류 해제", "RETRY yeogidot_r2_test 7", "3", "7", "0");
        assertThat(jdbc.queryForObject("SELECT status FROM r2_deletion_task WHERE id=7", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM r2_deletion_task WHERE id=7", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM r2_deletion_retry_audit", Integer.class)).isEqualTo(1);
        assertThat(operations.history(7).getFirst().previousAttempts()).isEqualTo(3);
        assertThat(operations.history(7).getFirst().reason()).isEqualTo("테스트 오류 해제");
        assertThat(directory.resolve(".r2-ops/pending-request.json")).doesNotExist();
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("never-connect-mysql");
    }

    @Test void DB커밋후_응답유실에도_새콘솔은_같은요청으로_기존이력만_확인한다() throws Exception {
        var uncertain = spy(operations);
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.getAndSet(false)) throw new IllegalStateException("Simulated lost response AFTER commit");
            return result;
        }).when(uncertain).retryFailed(anyLong(), anyInt(), anyString(), anyString(), anyString());
        run(uncertain, "4", "7", "테스트 오류 해제", "RETRY yeogidot_r2_test 7", "0");
        assertThat(directory.resolve(".r2-ops/pending-request.json")).exists();
        var original = operations.history(7).getFirst();
        // 작업자가 이미 완료한 상황에서도 같은 UUID의 재전송은 상태를 다시 PENDING으로 바꾸면 안 된다.
        jdbc.update("UPDATE r2_deletion_task SET status='COMPLETED',attempt_count=1 WHERE id=7");
        run(operations, "4", "RETRY yeogidot_r2_test 7", "0");
        assertThat(operations.history(7)).containsExactly(original);
        assertThat(jdbc.queryForObject("SELECT status FROM r2_deletion_task WHERE id=7", String.class)).isEqualTo("COMPLETED");
        assertThat(directory.resolve(".r2-ops/pending-request.json")).doesNotExist();
    }
}
