package com.yeogidot.yeogidot.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.service.R2DeletionOperationsService;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 셸 메뉴의 진입점. Spring 앱을 시작하지 않고 기존 CLI/운영 서비스를 사용한다. */
public final class R2DeletionOperationsConsole {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern LOCAL_URL = Pattern.compile(
            "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):([0-9]{1,5})/([A-Za-z][A-Za-z0-9_]{0,63})(?:\\?[^\\r\\n]*)?");

    private R2DeletionOperationsConsole() { }

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--prompt")))
                throw new IllegalArgumentException("Usage: r2-ops [--prompt]");
            Path app = Path.of(args[0]).toAbsolutePath().normalize();
            var input = terminalInput();
            var settings = args.length == 2 ? promptedSettings(input) : readSettings(app);
            int code = run(settings, app.resolve(".r2-ops"), input, System.out,
                    R2DeletionOperationsCli::operations);
            if (code != 0) System.exit(code);
        } catch (Exception failure) {
            // 설정/비밀번호/JDBC 예외 원문을 출력하지 않는다.
            System.err.println("Console stopped: " + failure.getClass().getSimpleName()
                    + ". Check local DB settings and private file permissions; --prompt is available.");
            System.exit(2);
        }
    }

    static R2DeletionOperationsCli.Settings readSettings(Path app) throws Exception {
        Path config = app.resolve("application-prod.properties");
        requirePrivate(config, false);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        var match = LOCAL_URL.matcher(properties.getProperty("spring.datasource.url", ""));
        if (!match.matches()) throw new IllegalArgumentException("Only direct local MySQL URLs allowed");
        String user = direct(properties.getProperty("spring.datasource.username"));
        String password = direct(properties.getProperty("spring.datasource.password"));
        return R2DeletionOperationsCli.settings(Map.of("R2_OPS_DB_NAME", match.group(2),
                "R2_OPS_DB_PORT", match.group(1), "R2_OPS_DB_USERNAME", user, "R2_OPS_DB_PASSWORD", password));
    }

    private static String direct(String value) {
        if (value == null || value.isBlank() || value.contains("${"))
            throw new IllegalArgumentException("Direct credentials required");
        return value;
    }

    static R2DeletionOperationsCli.Settings promptedSettings(Input input) throws Exception {
        String database = defaulted(input.read("DB 이름 [yeogidot]: "), "yeogidot");
        String port = defaulted(input.read("로컬 MySQL 포트 [3306]: "), "3306");
        String username = input.read("MySQL 계정: ").strip();
        char[] password = input.password("MySQL 비밀번호 (숨김 입력): ");
        try {
            return R2DeletionOperationsCli.settings(Map.of("R2_OPS_DB_NAME", database,
                    "R2_OPS_DB_PORT", port, "R2_OPS_DB_USERNAME", username,
                    "R2_OPS_DB_PASSWORD", new String(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static int run(R2DeletionOperationsCli.Settings settings, Path state, Input input, PrintStream out,
                   Function<R2DeletionOperationsCli.Settings, R2DeletionOperationsService> factory) throws Exception {
        privateDirectory(state);
        Path lockPath = state.resolve("console.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) requirePrivate(lockPath, false);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            makePrivate(lockPath, false);
            try (var lock = channel.tryLock()) {
                if (lock == null) {
                    out.println("다른 콘솔이 실행 중입니다. 기존 콘솔을 종료한 뒤 다시 실행하세요.");
                    return 2;
                }
                out.printf("대상: 127.0.0.1:%d/%s (계정: %s)%n", settings.port(), settings.database(), safe(settings.username()));
                out.println("이 도구는 파일을 직접 삭제하지 않습니다. 재처리 예약 후 기존 작업자가 삭제합니다.");
                var operations = factory.apply(settings);
                while (true) {
                    out.println("\n1. 전체 작업 현황\n2. 실패 작업 목록\n3. 재처리 이력 조회\n4. 실패 작업 재처리\n0. 종료");
                    String choice;
                    try { choice = input.read("선택: ").strip(); }
                    catch (EOFException end) { return 0; }
                    if (choice.equals("0")) return 0;
                    try {
                        switch (choice) {
                            case "1" -> print(out, operations.summary());
                            case "2" -> {
                                long after = Long.parseLong(defaulted(input.read("마지막 조회 작업 ID [0]: "), "0"));
                                print(out, operations.failedTasks(after, 20));
                                out.println("최대 20건입니다. 다음 페이지는 마지막 작업 ID를 입력하세요.");
                            }
                            case "3" -> print(out, operations.history(positive(input.read("작업 ID: "))));
                            case "4" -> retry(settings, state, input, out, operations);
                            default -> out.println("0~4 중 선택하세요.");
                        }
                    } catch (EOFException end) {
                        out.println("입력이 끝나 종료합니다. 승인하지 않은 요청은 보내지 않습니다.");
                        return 0;
                    } catch (Exception failure) {
                        out.println("처리 거절 또는 오류: " + failure.getClass().getSimpleName());
                        out.println("재처리 요청 파일이 남아 있으면 결과가 불확실할 수 있습니다. 4번은 같은 UUID로만 다시 요청합니다.");
                    }
                }
            }
        }
    }

    private static void retry(R2DeletionOperationsCli.Settings settings, Path state, Input input, PrintStream out,
                              R2DeletionOperationsService operations) throws Exception {
        Path receipt = state.resolve("pending-request.json");
        Request request;
        boolean saved = Files.exists(receipt, LinkOption.NOFOLLOW_LINKS);
        if (saved) {
            requirePrivate(receipt, false);
            if (Files.size(receipt) > 16_384) throw new IllegalArgumentException("Unexpected request file size");
            request = JSON.readValue(Files.readString(receipt, StandardCharsets.UTF_8), Request.class);
            request.validate(settings);
            out.println("직전 요청의 결과 확인이 끝나지 않았습니다. 새 요청을 만들지 않고 같은 UUID를 재사용합니다.");
        } else {
            long id = positive(input.read("재처리할 작업 ID: "));
            // 전체 목록을 가져오거나 임의 회차를 추측하지 않고 해당 FAILED 행만 확인한다.
            var candidates = operations.failedTasks(id - 1, 1);
            if (candidates.isEmpty() || candidates.getFirst().taskId() != id) {
                out.println("현재 FAILED인 작업이 아닙니다. 재처리를 요청하지 않았습니다.");
                return;
            }
            print(out, candidates.getFirst());
            String reason = input.read("원인 확인 및 조치 사유 (비밀번호 등 비밀정보 입력 금지): ").strip();
            request = new Request(settings.database(), settings.port(), settings.username(), id,
                    candidates.getFirst().retryCount(), UUID.randomUUID().toString(), reason);
            request.validate(settings);
        }
        print(out, request);
        String confirmation = "RETRY " + settings.database() + " " + request.taskId();
        if (!input.read("위 대상과 사유를 확인했다면 '" + confirmation + "' 입력: ").equals(confirmation)) {
            out.println("취소했습니다. 재처리를 요청하지 않았습니다.");
            return;
        }
        // DB 변경 전에 요청 ID/회차/사유를 안전하게 남긴다. 응답 유실/콘솔 종료 후에도 같은 요청을 재전송한다.
        if (!saved) save(receipt, request);
        Map<String, String> environment = new HashMap<>(Map.of("R2_OPS_DB_NAME", settings.database(),
                "R2_OPS_DB_PORT", Integer.toString(settings.port()), "R2_OPS_DB_USERNAME", settings.username(),
                "R2_OPS_DB_PASSWORD", settings.password(), "R2_OPS_ALLOW_WRITE", "true",
                "R2_OPS_CONFIRM", "RETRY:" + settings.database() + ":" + request.taskId() + ":" + request.requestId()));
        int result = R2DeletionOperationsCli.execute(new String[]{"retry", Long.toString(request.taskId()),
                Integer.toString(request.retryCount()), request.requestId(), request.reason()}, environment,
                out, out, ignored -> operations);
        if (result == 0) {
            Files.delete(receipt);
            out.println("요청을 확인했습니다. 삭제 완료 여부는 작업 상태와 실제 파일 부재로 별도 확인하세요.");
        } else {
            out.println("결과 미확정: 요청 파일을 유지했습니다. 이력 확인 후 4번에서 동일 요청을 다시 보낼 수 있습니다.");
            out.println("파일을 임의로 지우거나 새 UUID로 다시 요청하지 마세요: " + receipt);
        }
    }

    private static void save(Path receipt, Request request) throws Exception {
        Path temp = Files.createTempFile(receipt.getParent(), "request-", ".tmp");
        try {
            makePrivate(temp, false);
            Files.writeString(temp, JSON.writeValueAsString(request), StandardCharsets.UTF_8);
            // AtomicMove 미지원이면 DB에 요청하지 않는다. 불확실한 요청은 새 회차로 만들지 않는다.
            Files.move(temp, receipt, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void privateDirectory(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(path);
            makePrivate(path, true);
        }
        requirePrivate(path, true);
    }

    private static void makePrivate(Path path, boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    static void requirePrivate(Path path, boolean directory) throws Exception {
        if (Files.isSymbolicLink(path) || !(directory ? Files.isDirectory(path) : Files.isRegularFile(path)))
            throw new IllegalArgumentException("Regular private path required");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")
                && Files.getPosixFilePermissions(path).stream().anyMatch(permission ->
                    permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("Owner-only file permissions required");
    }

    private static long positive(String text) {
        long id = Long.parseLong(text.strip());
        if (id < 1) throw new IllegalArgumentException("Positive task ID required");
        return id;
    }

    private static String defaulted(String value, String fallback) {
        return value.isBlank() ? fallback : value.strip();
    }

    private static void print(PrintStream out, Object value) throws Exception {
        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static String safe(String text) {
        return text.replaceAll("\\p{Cntrl}", "?");
    }

    interface Input {
        String read(String prompt) throws Exception;
        char[] password(String prompt) throws Exception;
    }

    private static Input terminalInput() {
        var console = System.console();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new Input() {
            public String read(String prompt) throws Exception {
                String line;
                if (console != null) line = console.readLine("%s", prompt);
                else { System.out.print(prompt); System.out.flush(); line = reader.readLine(); }
                if (line == null) throw new EOFException();
                return line;
            }
            public char[] password(String prompt) throws Exception {
                if (console == null) throw new IllegalStateException("Hidden password input requires a terminal");
                char[] value = console.readPassword("%s", prompt);
                if (value == null) throw new EOFException();
                return value;
            }
        };
    }

    record Request(String database, int port, String username, long taskId, int retryCount, String requestId, String reason) {
        void validate(R2DeletionOperationsCli.Settings settings) {
            if (!settings.database().equals(database) || settings.port() != port || !settings.username().equals(username)
                    || taskId < 1 || retryCount < 0 || retryCount == Integer.MAX_VALUE || requestId == null
                    || !UUID.fromString(requestId).toString().equals(requestId) || reason == null || reason.isBlank()
                    || !reason.equals(reason.strip()) || reason.length() > 500
                    || reason.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid or mismatched recovery request");
        }
    }
}
