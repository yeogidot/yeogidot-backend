package com.yeogidot.yeogidot.service.r2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 가짜 접속 정보로 가드/실제 패키지 help/승인 거절만 확인한다. DB·R2 연결은 하지 않는다. */
class R2DeletionManualRecoveryProcessTest {
    @TempDir Path directory;

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("RUN_R2_MANUAL_LIVE_TEST", "true", "RUN_R2_MYSQL_TEST", "true",
                "R2_TEST_DB_NAME", "yeogidot_r2_test_20261015_1234abcd", "R2_TEST_DB_PORT", "3306",
                "R2_TEST_DB_USERNAME", "test-user", "R2_TEST_DB_PASSWORD", "never-print-test-password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yeogidot", "mysql", "yeogidot_r2_test_20261015_1234abcd_photo",
            "yeogidot_r2_test_20261015_1234abcd;DROP TABLE photo", "yeogidot_r2_test_20261015_1234ABCD"})
    void 운영DB와_임의_스키마를_프로세스_시작전에_거부한다(String schema) {
        var environment = environment(); environment.put("R2_TEST_DB_NAME", schema);
        assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.target(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 옵트인과_계정정보가_없으면_거부한다() {
        for (String key : environment().keySet()) {
            var environment = environment(); environment.remove(key);
            assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.target(environment))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String port : new String[]{"0", "65536", "3306/path", "not-a-port"}) {
            var environment = environment(); environment.put("R2_TEST_DB_PORT", port);
            assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.target(environment))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void 자식프로세스는_테스트_DB정보만_받고_쓰기승인은_대상요청에만_묶인다() throws Exception {
        var target = R2DeletionManualRecoveryProcess.target(environment());
        String request = UUID.randomUUID().toString();
        var builder = R2DeletionManualRecoveryProcess.builder(target, R2DeletionManualRecoveryProcess.testJar(),
                directory, directory.resolve("result.log"), true, "retry", "7", "0", request, "Test recovery");
        assertThat(builder.command()).doesNotContain("never-print-test-password");
        assertThat(builder.environment()).containsEntry("R2_OPS_DB_NAME", "yeogidot_r2_test_20261015_1234abcd_photo")
                .containsEntry("R2_OPS_ALLOW_WRITE", "true")
                .containsEntry("R2_OPS_CONFIRM", "RETRY:yeogidot_r2_test_20261015_1234abcd_photo:7:" + request);
        assertThat(builder.environment().keySet()).allMatch(key -> key.startsWith("R2_OPS_")
                || java.util.Set.of("SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "LANG", "LC_ALL").contains(key));
        assertThat(builder.environment()).doesNotContainKeys("R2_LIVE_ACCESS_KEY_ID", "R2_LIVE_SECRET_ACCESS_KEY",
                "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "LOADER_MAIN", "LOADER_PATH");
        var readOnly = R2DeletionManualRecoveryProcess.builder(target, R2DeletionManualRecoveryProcess.testJar(),
                directory, directory.resolve("read.log"), false, "status");
        assertThat(readOnly.environment()).doesNotContainKeys("R2_OPS_ALLOW_WRITE", "R2_OPS_CONFIRM");
    }

    @Test void 임의_명령과_임시폴더_밖_출력을_거부한다() throws Exception {
        var target = R2DeletionManualRecoveryProcess.target(environment());
        Path jar = R2DeletionManualRecoveryProcess.testJar();
        assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.builder(target, jar, directory,
                directory.resolve("out.log"), false, "delete", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.builder(target, jar, directory,
                directory.resolve("out.log"), true, "status")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.builder(target, jar, directory,
                directory.resolve("../out.log"), false, "help")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> R2DeletionManualRecoveryProcess.builder(target, jar, directory,
                directory.resolve("out.log"), true, "retry", "1", "0", "bad", "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 실제_패키지_help는_앱이나_DB를_시작하지_않고_동작한다() throws Exception {
        var result = R2DeletionManualRecoveryProcess.run(R2DeletionManualRecoveryProcess.target(environment()),
                directory, false, "help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("status | failed", "No app settings loaded")
                .doesNotContain("Spring Boot", "never-print-test-password");
    }

    @Test void 실제_패키지의_승인없는_재처리는_DB접속전에_거부된다() throws Exception {
        var result = R2DeletionManualRecoveryProcess.run(R2DeletionManualRecoveryProcess.target(environment()),
                directory, false, "retry", "1", "0", UUID.randomUUID().toString(), "Test recovery");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("IllegalArgumentException").doesNotContain("Target:",
                "CannotGetJdbcConnectionException", "never-print-test-password", "Spring Boot");
    }
}
