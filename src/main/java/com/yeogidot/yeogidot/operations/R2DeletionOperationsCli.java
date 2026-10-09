package com.yeogidot.yeogidot.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.service.R2DeletionOperationsService;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Boot 앱/웹/스케줄러/Redis/R2를 시작하지 않는 운영자 전용 단발성 도구. */
public final class R2DeletionOperationsCli {
    private R2DeletionOperationsCli() { }
    public static void main(String[] args) {
        int code = execute(args, System.getenv(), System.out, System.err, settings -> {
            var source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + settings.port()
                    + "/" + settings.database() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                    + "&connectTimeout=3000&socketTimeout=10000", settings.username(), settings.password());
            return new R2DeletionOperationsService(source, Clock.systemUTC());
        });
        if (code != 0) System.exit(code);
    }

    static int execute(String[] args, Map<String, String> env, PrintStream out, PrintStream err,
                       Function<Settings, R2DeletionOperationsService> factory) {
        if (args.length == 0 || args[0].equals("help")) {
            out.println("status | failed [afterId limit] | history taskId | retry taskId retryCount requestUUID reason");
            out.println("No app settings loaded. DB host fixed to 127.0.0.1. Secrets must be environment variables.");
            return 0;
        }
        try {
            String command = args[0];
            if (!(command.equals("status") && args.length == 1)
                    && !(command.equals("failed") && (args.length == 1 || args.length == 3))
                    && !(command.equals("history") && args.length == 2)
                    && !(command.equals("retry") && args.length == 5))
                throw new IllegalArgumentException("Invalid command");
            Settings settings = settings(env);
            long id = command.equals("retry") || command.equals("history") ? Long.parseLong(args[1]) : 0;
            if ((command.equals("retry") || command.equals("history")) && id < 1)
                throw new IllegalArgumentException("Positive taskId required");
            int round = command.equals("retry") ? Integer.parseInt(args[2]) : 0;
            if (command.equals("retry")) {
                if (round < 0 || round == Integer.MAX_VALUE
                        || !UUID.fromString(args[3]).toString().equals(args[3]) || args[4].isBlank()
                        || args[4].length() > 500 || args[4].codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid recovery request");
                if (!"true".equals(env.get("R2_OPS_ALLOW_WRITE"))
                        || !("RETRY:" + settings.database() + ":" + id + ":" + args[3]).equals(env.get("R2_OPS_CONFIRM")))
                    throw new IllegalArgumentException("Explicit target/task/request confirmation required");
            }
            long after = command.equals("failed") && args.length == 3 ? Long.parseLong(args[1]) : 0;
            int limit = command.equals("failed") && args.length == 3 ? Integer.parseInt(args[2]) : 20;
            if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid list range");
            out.printf("Target: 127.0.0.1:%d/%s; command=%s%n", settings.port(), settings.database(), command);
            var operations = factory.apply(settings);
            Object result = switch (command) {
                case "status" -> operations.summary();
                case "failed" -> operations.failedTasks(after, limit);
                case "history" -> operations.history(id);
                case "retry" -> operations.retryFailed(id, round, args[3],
                        System.getProperty("user.name", "unknown") + " (db:" + settings.username() + ")", args[4]);
                default -> throw new IllegalArgumentException("Unknown command");
            };
            out.println(new ObjectMapper().findAndRegisterModules().writeValueAsString(result));
            if (command.equals("retry")) out.println("Recovery request recorded or replayed. Check current status and file absence separately.");
            return 0;
        } catch (Exception failure) {
            // JDBC 예외 메시지에는 설정/비밀번호가 섞일 수 있어 종류만 출력한다.
            err.println("Operation refused or failed: " + failure.getClass().getSimpleName());
            return 2;
        }
    }

    static Settings settings(Map<String, String> env) {
        String database = required(env, "R2_OPS_DB_NAME");
        if (!database.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || database.matches("(?i)mysql|sys|performance_schema|information_schema"))
            throw new IllegalArgumentException("Invalid database");
        String value = env.getOrDefault("R2_OPS_DB_PORT", "3306");
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid port");
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return new Settings(database, port, required(env, "R2_OPS_DB_USERNAME"), required(env, "R2_OPS_DB_PASSWORD"));
    }
    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    // 비밀번호가 포함된 설정 객체의 자동 toString을 사용하지 않는다.
    static final class Settings {
        private final String database;
        private final int port;
        private final String username;
        private final String password;
        Settings(String database, int port, String username, String password) {
            this.database = database; this.port = port; this.username = username; this.password = password;
        }
        String database() { return database; }
        int port() { return port; }
        String username() { return username; }
        String password() { return password; }
    }
}
