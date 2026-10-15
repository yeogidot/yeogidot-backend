package com.yeogidot.yeogidot.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.service.R2DeletionOperationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 메모리 모의 서비스와 임시 설정 파일만 사용한다. MySQL/R2/서버에 접속하지 않는다. */
class R2DeletionOperationsConsoleTest {
    @TempDir Path app;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final R2DeletionOperationsService operations = mock(R2DeletionOperationsService.class);

    private R2DeletionOperationsCli.Settings settings() {
        return R2DeletionOperationsCli.settings(Map.of("R2_OPS_DB_NAME", "yeogidot_r2_test",
                "R2_OPS_DB_USERNAME", "operator", "R2_OPS_DB_PASSWORD", "never-print-secret"));
    }

    private R2DeletionOperationsConsole.Input input(String... lines) {
        var queue = new ArrayDeque<>(List.of(lines));
        return new R2DeletionOperationsConsole.Input() {
            public String read(String prompt) throws Exception {
                if (queue.isEmpty()) throw new EOFException();
                return queue.removeFirst();
            }
            public char[] password(String prompt) { throw new AssertionError("No password prompt allowed"); }
        };
    }

    private void run(String... lines) throws Exception {
        assertThat(R2DeletionOperationsConsole.run(settings(), app.resolve(".r2-ops"), input(lines),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), ignored -> operations)).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("never-print-secret");
    }

    private void failedTask() {
        when(operations.failedTasks(6, 1)).thenReturn(List.of(new R2DeletionOperationsService.FailedTask(
                7, "https://r2-test.invalid/fixture", 3, "InjectedFailure", Instant.EPOCH, 2)));
    }

    @Test void 종료와_입력종료는_DB조회나_변경없이_끝난다() throws Exception {
        run("0");
        run();
        verifyNoInteractions(operations);
    }

    @Test void 메뉴에서_조회와_다음페이지와_이력을_호출한다() throws Exception {
        run("1", "2", "15", "3", "7", "0");
        verify(operations).summary();
        verify(operations).failedTasks(15, 20);
        verify(operations).history(7);
        verifyNoMoreInteractions(operations);
    }

    @ParameterizedTest @ValueSource(strings = {"", "RUN", "retry yeogidot_r2_test 7", "RETRY wrong_db 7"})
    void 정확한_대상_확인이_없으면_쓰기나_요청파일_저장을_하지_않는다(String confirmation) throws Exception {
        failedTask();
        run("4", "7", "권한 복구", confirmation, "0");
        verify(operations, never()).retryFailed(anyLong(), anyInt(), anyString(), anyString(), anyString());
        assertThat(app.resolve(".r2-ops/pending-request.json")).doesNotExist();
    }

    @Test void 실패가_아닌_작업이나_잘못된_입력은_재처리를_보내지_않는다() throws Exception {
        when(operations.failedTasks(6, 1)).thenReturn(List.of(new R2DeletionOperationsService.FailedTask(
                8, "fixture", 3, "error", Instant.EPOCH, 0)));
        run("4", "7", "3", "-1", "4", "not-an-id", "0");
        verify(operations, never()).retryFailed(anyLong(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void 승인된_요청은_최신회차와_UUID를_사용하고_비밀번호를_저장하지_않는다() throws Exception {
        failedTask();
        when(operations.retryFailed(eq(7L), eq(2), anyString(), anyString(), eq("권한 복구")))
                .thenAnswer(call -> {
                    String request = call.getArgument(2);
                    assertThat(UUID.fromString(request).toString()).isEqualTo(request);
                    String receipt = Files.readString(app.resolve(".r2-ops/pending-request.json"));
                    assertThat(receipt).contains(request, "권한 복구").doesNotContain("never-print-secret");
                    return new R2DeletionOperationsService.RetryAudit(1, request, 7, 3, "operator", "권한 복구",
                            3, "error", Instant.EPOCH, Instant.EPOCH);
                });
        run("4", "7", "권한 복구", "RETRY yeogidot_r2_test 7", "0");
        verify(operations).retryFailed(eq(7L), eq(2), anyString(), contains("db:operator"), eq("권한 복구"));
        assertThat(app.resolve(".r2-ops/pending-request.json")).doesNotExist();
    }

    @Test void 응답실패_후_새_콘솔에서도_동일_UUID와_회차로만_다시_요청한다() throws Exception {
        failedTask();
        when(operations.retryFailed(anyLong(), anyInt(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("never-print-secret"));
        run("4", "7", "권한 복구", "RETRY yeogidot_r2_test 7", "0");
        assertThat(app.resolve(".r2-ops/pending-request.json")).exists();
        run("4", "RETRY yeogidot_r2_test 7", "0");
        var ids = ArgumentCaptor.forClass(String.class);
        verify(operations, times(2)).retryFailed(eq(7L), eq(2), ids.capture(), anyString(), eq("권한 복구"));
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
        verify(operations, times(1)).failedTasks(6, 1);
    }

    @Test void 요청파일이_다른_DB나_계정의_것이면_쓰기전에_거절한다() throws Exception {
        run("0");
        var request = new R2DeletionOperationsConsole.Request("wrong_db", 3306, "operator", 7, 0,
                UUID.randomUUID().toString(), "권한 복구");
        Files.writeString(app.resolve(".r2-ops/pending-request.json"), new ObjectMapper().writeValueAsString(request));
        if (Files.getFileStore(app).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(app.resolve(".r2-ops/pending-request.json"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        run("4", "0");
        verifyNoInteractions(operations);
    }

    @Test void 같은_상태폴더를_사용하는_콘솔은_동시에_변경하지_못한다() throws Exception {
        run("0");
        try (var channel = FileChannel.open(app.resolve(".r2-ops/console.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThatThrownBy(() -> run("4", "7")).isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
        }
        verifyNoInteractions(operations);
    }

    @Test void 승인입력이_끝나면_재처리하지_않는다() throws Exception {
        failedTask();
        run("4", "7", "권한 복구");
        verify(operations, never()).retryFailed(anyLong(), anyInt(), anyString(), anyString(), anyString());
        assertThat(app.resolve(".r2-ops/pending-request.json")).doesNotExist();
    }

    @Test @EnabledOnOs(OS.LINUX)
    void 리눅스에서_공개된_설정파일과_심볼릭링크는_읽지_않는다() throws Exception {
        writeConfig("jdbc:mysql://127.0.0.1:3306/yeogidot_r2_test", "secret");
        Path file = app.resolve("application-prod.properties");
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> R2DeletionOperationsConsole.readSettings(app)).isInstanceOf(IllegalArgumentException.class);
        Files.move(file, app.resolve("original.properties"));
        Files.createSymbolicLink(file, app.resolve("original.properties"));
        assertThatThrownBy(() -> R2DeletionOperationsConsole.readSettings(app)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 설정은_자바_properties_이스케이프를_해석하되_다른_비밀은_사용하지_않는다() throws Exception {
        writeConfig("jdbc:mysql://localhost:3306/yeogidot_r2_test?serverTimezone=Asia/Seoul", "pa\\\\ss\\!word");
        var config = R2DeletionOperationsConsole.readSettings(app);
        assertThat(config.password()).isEqualTo("pa\\ss!word");
        assertThat(config.database()).isEqualTo("yeogidot_r2_test");
        assertThat(config.port()).isEqualTo(3306);
    }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:mysql://prod.example:3306/yeogidot", "jdbc:mysql://localhost:3306/mysql",
            "jdbc:mysql://localhost:0/yeogidot", "jdbc:mysql://localhost:3306/yeogidot;other",
            "jdbc:mysql://localhost:3306/${DB_NAME}", "jdbc:mysql://localhost:3306/yeogidot,other"})
    void 원격주소_시스템DB_잘못된포트_플레이스홀더는_거절한다(String url) throws Exception {
        writeConfig(url, "secret");
        assertThatThrownBy(() -> R2DeletionOperationsConsole.readSettings(app)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(operations);
    }

    @Test void 환경변수_치환이_필요한_비밀번호는_추측하지_않는다() throws Exception {
        writeConfig("jdbc:mysql://127.0.0.1:3306/yeogidot_r2_test", "${DB_PASSWORD}");
        assertThatThrownBy(() -> R2DeletionOperationsConsole.readSettings(app)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 수동입력은_비밀번호_전용_입력을_사용하고_문자배열을_비운다() throws Exception {
        var queue = new ArrayDeque<>(List.of("", "", "operator"));
        char[] password = "hidden-secret".toCharArray();
        var config = R2DeletionOperationsConsole.promptedSettings(new R2DeletionOperationsConsole.Input() {
            public String read(String prompt) { return queue.removeFirst(); }
            public char[] password(String prompt) { return password; }
        });
        assertThat(config.password()).isEqualTo("hidden-secret");
        assertThat(password).containsOnly('\0');
    }

    private void writeConfig(String url, String password) throws Exception {
        Path file = app.resolve("application-prod.properties");
        Files.writeString(file, "spring.datasource.url=" + url + "\nspring.datasource.username=operator\n"
                + "spring.datasource.password=" + password + "\nr2.secret-key=never-used-r2-secret\n");
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}
