package com.yeogidot.yeogidot.service.r2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** 테스트 DB에만 접속하는 실제 패키지 CLI 실행기. R2 키와 운영 환경변수는 자식에 전달하지 않는다. */
final class R2DeletionManualRecoveryProcess {
    private static final String MAIN = "com.yeogidot.yeogidot.operations.R2DeletionOperationsCli";

    static Target target(Map<String, String> environment) {
        if (!"true".equals(environment.get("RUN_R2_MANUAL_LIVE_TEST"))
                || !"true".equals(environment.get("RUN_R2_MYSQL_TEST"))) {
            throw new IllegalArgumentException("Explicit manual recovery test opt-ins required");
        }
        String base = required(environment, "R2_TEST_DB_NAME");
        if (!base.matches("yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Only a generated test schema is allowed");
        }
        String port = required(environment, "R2_TEST_DB_PORT");
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("Invalid local test port");
        }
        return new Target(base + "_photo", port, required(environment, "R2_TEST_DB_USERNAME"),
                required(environment, "R2_TEST_DB_PASSWORD"));
    }

    static Path testJar() throws Exception {
        String location = System.getProperty("r2.recovery.test.jar");
        if (location == null || location.isBlank()) throw new IllegalStateException("Run bootJar and test with Gradle");
        Path jar = Path.of(location).toAbsolutePath();
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Test package missing; run bootJar first");
        try (var zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("BOOT-INF/classes/" + MAIN.replace('.', '/') + ".class") == null
                    || zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") == null) {
                throw new IllegalStateException("Expected packaged CLI not found");
            }
        }
        return jar;
    }

    static ProcessBuilder builder(Target target, Path jar, Path directory, Path output,
                                  boolean approveWrite, String... arguments) {
        validateArguments(arguments);
        if (approveWrite && !arguments[0].equals("retry")) {
            throw new IllegalArgumentException("Write approval is only for retry");
        }
        Path checkedDirectory = directory.toAbsolutePath().normalize();
        Path checkedOutput = output.toAbsolutePath().normalize();
        if (!checkedOutput.startsWith(checkedDirectory) || checkedOutput.equals(checkedDirectory)) {
            throw new IllegalArgumentException("Output must stay in the temporary test directory");
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx192m", "-Dfile.encoding=UTF-8", "-Dloader.main=" + MAIN, "-cp",
                jar.toAbsolutePath().toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(checkedDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(checkedOutput.toFile());
        var child = process.environment();
        child.clear();
        // Windows 실행에 필요한 변수와 문자 인코딩/임시 폴더만 전달한다.
        for (String name : new String[]{"SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "LANG", "LC_ALL"}) {
            String value = System.getenv(name);
            if (value != null) child.put(name, value);
        }
        child.put("R2_OPS_DB_NAME", target.schema);
        child.put("R2_OPS_DB_PORT", target.port);
        child.put("R2_OPS_DB_USERNAME", target.username);
        child.put("R2_OPS_DB_PASSWORD", target.password);
        if (approveWrite) {
            child.put("R2_OPS_ALLOW_WRITE", "true");
            child.put("R2_OPS_CONFIRM", "RETRY:" + target.schema + ":" + arguments[1] + ":" + arguments[3]);
        }
        return process;
    }

    static Result run(Target target, Path directory, boolean approveWrite, String... arguments) throws Exception {
        Path output = directory.resolve(UUID.randomUUID() + ".log");
        Process process = builder(target, testJar(), directory, output, approveWrite, arguments).start();
        boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        if (!finished) throw new IllegalStateException("Test CLI process timed out");
        return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    private static void validateArguments(String[] arguments) {
        if (arguments.length == 1 && List.of("help", "status", "failed").contains(arguments[0])) return;
        if (arguments.length == 2 && arguments[0].equals("history") && Long.parseLong(arguments[1]) > 0) return;
        if (arguments.length == 5 && arguments[0].equals("retry") && Long.parseLong(arguments[1]) > 0
                && Integer.parseInt(arguments[2]) >= 0 && Integer.parseInt(arguments[2]) < Integer.MAX_VALUE
                && UUID.fromString(arguments[3]).toString().equals(arguments[3])
                && !arguments[4].isBlank() && arguments[4].length() <= 500
                && arguments[4].codePoints().noneMatch(Character::isISOControl)) return;
        throw new IllegalArgumentException("Unexpected test CLI arguments");
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing test setting: " + key);
        return value;
    }

    // 비밀번호가 포함된 record/toString은 만들지 않는다.
    static final class Target {
        private final String schema;
        private final String port;
        private final String username;
        private final String password;
        private Target(String schema, String port, String username, String password) {
            this.schema = schema; this.port = port; this.username = username; this.password = password;
        }
    }

    record Result(int exitCode, String output) {
        JsonNode json() throws Exception {
            String value = output.lines().filter(line -> line.startsWith("{") || line.startsWith("["))
                    .findFirst().orElseThrow(() -> new IllegalStateException("CLI JSON result missing"));
            return new ObjectMapper().readTree(value);
        }
    }
}
