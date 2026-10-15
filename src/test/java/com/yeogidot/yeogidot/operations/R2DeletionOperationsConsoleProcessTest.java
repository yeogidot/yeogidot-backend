package com.yeogidot.yeogidot.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.*;

/** 실제 bootJar 진입점/설정 읽기/종료만 검증한다. 메뉴 0은 DB에 접속하지 않는다. */
class R2DeletionOperationsConsoleProcessTest {
    @TempDir Path directory;

    @Test void 실제_JAR_메뉴는_비밀을_출력하거나_앱과_DB를_시작하지_않고_종료한다() throws Exception {
        launch(false);
    }

    @Test void 실제_셸스크립트에서_JAR_메뉴까지_연결하고_기존_JVM옵션과_쓰기승인을_제거한다() throws Exception {
        launch(true);
    }

    private void launch(boolean wrapper) throws Exception {
        Path app = Files.createDirectory(directory.resolve("app"));
        Path work = Files.createDirectory(directory.resolve("isolated"));
        Path config = app.resolve("application-prod.properties");
        Files.writeString(config, "spring.datasource.url=jdbc:mysql://127.0.0.1:1/yeogidot_r2_test\n"
                + "spring.datasource.username=test-operator\nspring.datasource.password=never-print-password\n"
                + "r2.secret-key=never-print-r2-key\n");
        if (Files.getFileStore(config).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx128m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dloader.main=com.yeogidot.yeogidot.operations.R2DeletionOperationsConsole", "-cp",
                System.getProperty("r2.recovery.test.jar"),
                "org.springframework.boot.loader.launch.PropertiesLauncher", app.toString());
        if (wrapper) {
            Path bash = System.getProperty("os.name").startsWith("Windows")
                    ? Path.of("C:/Program Files/Git/bin/bash.exe") : Path.of("/bin/bash");
            Assumptions.assumeTrue(Files.isExecutable(bash), "Bash is needed for the wrapper process test");
            Files.copy(Path.of(System.getProperty("r2.recovery.test.jar")), app.resolve("yeogidot.jar"));
            Path script = app.resolve("r2-ops");
            // Windows 체크아웃의 CRLF 여부와 관계없이 실제 배포와 동일한 LF 스크립트를 사용한다.
            Files.writeString(script, Files.readString(Path.of("scripts/r2-ops")).replace("\r\n", "\n"));
            builder.command(bash.toString(), "-c", "export PATH=/usr/bin:/bin:$PATH; bash \"$1\"", "r2-wrapper-test",
                    script.toString().replace('\\', '/'));
        }
        builder.environment().clear();
        for (String key : new String[]{"SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "LANG", "LC_ALL"}) {
            String value = System.getenv(key);
            if (value != null) builder.environment().put(key, value);
        }
        if (wrapper) {
            builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin")
                    + java.io.File.pathSeparator + System.getenv("PATH"));
            builder.environment().put("JAVA_TOOL_OPTIONS", "-XX:DefinitelyInvalidTestOption");
            builder.environment().put("JDK_JAVA_OPTIONS", "-XX:DefinitelyInvalidTestOption");
            builder.environment().put("LOADER_MAIN", "invalid.test.Main");
            builder.environment().put("R2_OPS_ALLOW_WRITE", "true");
            builder.environment().put("R2_OPS_CONFIRM", "unsafe-inherited-approval");
            builder.environment().put("R2_OPS_DB_PASSWORD", "unsafe-inherited-password");
        }
        Path log = directory.resolve("console.log");
        var process = builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write("0\n".getBytes(StandardCharsets.UTF_8)); }
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage("Packaged console failed: %s",
                    Files.readString(log, StandardCharsets.UTF_8)).isZero();
            assertThat(Files.readString(log, StandardCharsets.UTF_8)).contains("1. 전체 작업 현황", "0. 종료", "127.0.0.1:1/yeogidot_r2_test")
                    .doesNotContain("never-print-password", "never-print-r2-key", "Spring Boot", "CannotGetJdbcConnectionException",
                            "unsafe-inherited", "DefinitelyInvalidTestOption");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
