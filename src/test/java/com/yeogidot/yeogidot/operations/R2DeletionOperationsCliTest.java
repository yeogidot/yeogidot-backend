package com.yeogidot.yeogidot.operations;

import com.yeogidot.yeogidot.service.R2DeletionOperationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class R2DeletionOperationsCliTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("R2_OPS_DB_NAME", "yeogidot_r2_test", "R2_OPS_DB_USERNAME", "operator",
                "R2_OPS_DB_PASSWORD", "never-print-this"));
    }
    @Test void 도움말은_설정이나_DB없이_실행한다() {
        var output = new ByteArrayOutputStream();
        assertThat(R2DeletionOperationsCli.execute(new String[]{"help"}, Map.of(), new PrintStream(output),
                new PrintStream(output), settings -> { throw new AssertionError("No DB allowed"); })).isZero();
        assertThat(output.toString()).contains("retry");
    }
    @Test void 명시적_변경허용과_정확한_확인이_없으면_DB연결전에_거절한다() {
        var env = environment();
        String request = UUID.randomUUID().toString();
        var output = new ByteArrayOutputStream();
        var args = new String[]{"retry", "1", "0", request, "원인 해결"};
        assertThat(R2DeletionOperationsCli.execute(args, env, new PrintStream(output), new PrintStream(output),
                settings -> { throw new AssertionError("No DB allowed"); })).isEqualTo(2);
        env.put("R2_OPS_ALLOW_WRITE", "true");
        env.put("R2_OPS_CONFIRM", "RETRY:wrong_database:1:" + request);
        assertThat(R2DeletionOperationsCli.execute(args, env, new PrintStream(output), new PrintStream(output),
                settings -> { throw new AssertionError("No DB allowed"); })).isEqualTo(2);
        assertThat(output.toString()).doesNotContain("never-print-this");
    }
    @ParameterizedTest @ValueSource(strings = {"mysql", "sys", "information_schema", "performance_schema", "db;DROP", "db/remote"})
    void 잘못된_DB이름은_연결전에_거절한다(String database) {
        var env = environment(); env.put("R2_OPS_DB_NAME", database);
        var output = new ByteArrayOutputStream();
        assertThat(R2DeletionOperationsCli.execute(new String[]{"status"}, env, new PrintStream(output),
                new PrintStream(output), settings -> { throw new AssertionError("No DB allowed"); })).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "65536", "abc", "3306/remote"})
    void 잘못된_포트는_연결전에_거절한다(String port) {
        var env = environment(); env.put("R2_OPS_DB_PORT", port);
        var output = new ByteArrayOutputStream();
        assertThat(R2DeletionOperationsCli.execute(new String[]{"status"}, env, new PrintStream(output),
                new PrintStream(output), settings -> { throw new AssertionError("No DB allowed"); })).isEqualTo(2);
    }
    @Test void 승인된_요청은_OS계정_표기와_함께_서비스에_전달한다() {
        var env = environment(); String request = UUID.randomUUID().toString();
        env.put("R2_OPS_ALLOW_WRITE", "true");
        env.put("R2_OPS_CONFIRM", "RETRY:yeogidot_r2_test:1:" + request);
        var operations = mock(R2DeletionOperationsService.class);
        when(operations.retryFailed(eq(1L), eq(0), eq(request), anyString(), eq("권한 복구")))
                .thenReturn(new R2DeletionOperationsService.RetryAudit(1, request, 1, 1, "operator", "권한 복구",
                        3, "IllegalStateException", Instant.now(), Instant.now()));
        var output = new ByteArrayOutputStream();
        assertThat(R2DeletionOperationsCli.execute(new String[]{"retry", "1", "0", request, "권한 복구"},
                env, new PrintStream(output), new PrintStream(output), settings -> operations)).isZero();
        verify(operations).retryFailed(eq(1L), eq(0), eq(request), contains("db:operator"), eq("권한 복구"));
        assertThat(output.toString()).contains("recorded or replayed").doesNotContain("never-print-this");
    }
    @Test void DB조회_오류에도_비밀번호와_오류원문을_노출하지_않는다() {
        var output = new ByteArrayOutputStream(); var connected = new AtomicBoolean();
        assertThat(R2DeletionOperationsCli.execute(new String[]{"status"}, environment(), new PrintStream(output),
                new PrintStream(output), settings -> {
                    connected.set(true); throw new IllegalStateException("never-print-this");
                })).isEqualTo(2);
        assertThat(connected).isTrue();
        assertThat(output.toString()).doesNotContain("never-print-this");
    }
}
