package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 동시성 구조만 확인한다. 처리량·메모리 벤치마크나 실제 외부 서비스 테스트가 아니다. */
class PhotoUploadConcurrencyBaselineTest {
    @TempDir Path directory;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"validation-outside-limit", "upload-limit", "upload-failure-release", "postprocess-failure-release"})
    void 실제_업로드서비스의_제한범위와_실패후_권한반환을_별도_JVM에서_확인한다(String scenario) throws Exception {
        // 기존 Gradle의 재시작 테스트용 런타임 클래스패스를 재사용한다. Spring 설정은 로드하지 않는다.
        String classpath = System.getProperty("r2.recovery.test.classpath");
        assertThat(classpath).as("Gradle test 작업으로 실행해야 합니다.").isNotBlank();
        Path work = Files.createDirectory(directory.resolve(scenario));
        Path result = work.resolve("result.json");
        Path log = work.resolve("process.log");
        Path args = work.resolve("java.args");
        Path reportDirectory = Path.of("build", "reports", "upload-concurrency-baseline").toAbsolutePath();
        Files.createDirectories(reportDirectory);
        // 재실행 실패 시 이전 성공 JSON을 이번 결과로 오인하지 않게 한다.
        Files.deleteIfExists(reportDirectory.resolve(scenario + ".json"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Files.writeString(args, List.of("-Xmx256m", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
                        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=8", "-cp", classpath,
                        PhotoUploadConcurrencyProbe.class.getName(), scenario, result.toString()).stream()
                .map(PhotoUploadConcurrencyBaselineTest::quote).collect(Collectors.joining(System.lineSeparator())), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "@" + args)
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        for (String name : new String[]{"SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "LANG", "LC_ALL"}) {
            String value = System.getenv(name);
            if (value != null) builder.environment().put(name, value);
        }
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(40, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("테스트 자식 JVM 종료").isTrue();
            }
        }
        Files.copy(log, reportDirectory.resolve(scenario + ".log"), StandardCopyOption.REPLACE_EXISTING);
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(finished).withFailMessage("자식 JVM 시간 초과:%n%s", output).isTrue();
        assertThat(process.exitValue()).withFailMessage("자식 JVM 실패:%n%s", output).isZero();
        JsonNode evidence = new ObjectMapper().readTree(result.toFile());
        assertThat(evidence.path("scenario").asText()).isEqualTo(scenario);
        assertThat(evidence.path("passed").asBoolean()).isTrue();
        assertThat(evidence.path("externalServices").asText()).isEqualTo("mock-only");
        Files.copy(result, reportDirectory.resolve(scenario + ".json"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("UPLOAD_BASELINE_RESULT " + evidence);
    }

    private static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
