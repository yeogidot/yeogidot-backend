package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class R2DeletionRestartTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"after-commit", "after-claim", "after-file-delete", "after-failure"})
    void 강제종료한_JVM의_삭제작업을_새_JVM에서_복구한다(String scenario) throws Exception {
        runProcess("seed", scenario, 17);
        runProcess("recover", scenario, 0);
    }

    private void runProcess(String action, String scenario, int expectedExit) throws Exception {
        String classpath = System.getProperty("r2.recovery.test.classpath");
        assertThat(classpath).as("Gradle test 작업으로 실행해야 합니다.").isNotBlank();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path arguments = directory.resolve(action + ".args");
        // Windows의 명령 길이 제한을 피한다. 로컬 클래스패스만 포함하며 계정 정보는 없다.
        Files.writeString(arguments, List.of("-cp", classpath, R2RecoveryProcessFixture.class.getName(),
                        action, scenario, directory.toAbsolutePath().toString()).stream()
                .map(R2DeletionRestartTest::quoteArgument).collect(Collectors.joining(System.lineSeparator())));
        Path log = directory.resolve(action + ".log");
        Process process = new ProcessBuilder(java.toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(45, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String output = Files.readString(log);
        assertThat(finished).withFailMessage("Child JVM timed out:%n%s", output).isTrue();
        assertThat(process.exitValue()).withFailMessage("Child JVM failed:%n%s", output).isEqualTo(expectedExit);
        output.lines().filter(line -> line.startsWith("BEFORE_RESTART") || line.startsWith("AFTER_RESTART"))
                .forEach(System.out::println);
    }

    private static String quoteArgument(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
