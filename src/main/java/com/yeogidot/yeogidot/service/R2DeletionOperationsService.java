package com.yeogidot.yeogidot.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

/** 운영자 콘솔과 모니터 전용. 일반 사용자 HTTP API로 노출하지 않는다. */
@Service
public class R2DeletionOperationsService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public R2DeletionOperationsService(DataSource source, @Qualifier("r2DeletionClock") Clock clock) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.clock = clock;
    }

    public Summary summary() {
        // 건수와 최근 실패를 같은 SQL의 스냅샷에서 읽는다. 중간 상태 변경으로 서로 엇갈리지 않게 한다.
        return jdbc.queryForObject("SELECT "
                + "COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending, "
                + "COALESCE(SUM(CASE WHEN status='PROCESSING' THEN 1 ELSE 0 END),0) AS processing, "
                + "COALESCE(SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),0) AS completed, "
                + "COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failed, "
                + "(SELECT id FROM r2_deletion_task WHERE status='FAILED' ORDER BY updated_at DESC,id DESC LIMIT 1) AS latest_id, "
                + "(SELECT updated_at FROM r2_deletion_task WHERE status='FAILED' ORDER BY updated_at DESC,id DESC LIMIT 1) AS latest_time "
                + "FROM r2_deletion_task", (rs, row) -> {
                    long latestId = rs.getLong("latest_id");
                    FailureMarker marker = rs.wasNull() ? null : new FailureMarker(latestId, instant(rs, "latest_time"));
                    return new Summary(rs.getLong("pending"), rs.getLong("processing"), rs.getLong("completed"),
                            rs.getLong("failed"), marker);
                });
    }

    /** 처리 대상을 확인할 때 사용할 실패 목록. OFFSET 없이 ID 기준으로 제한해 조회한다. */
    public List<FailedTask> failedTasks(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid list range");
        return jdbc.query("SELECT t.id, t.file_url, t.attempt_count, t.last_error, t.updated_at, "
                        + "COALESCE((SELECT MAX(a.retry_number) FROM r2_deletion_retry_audit a "
                        + "WHERE a.task_id=t.id), 0) AS retry_count FROM r2_deletion_task t "
                        + "WHERE t.status='FAILED' AND t.id>? ORDER BY t.id LIMIT ?",
                (rs, row) -> new FailedTask(rs.getLong("id"), rs.getString("file_url"),
                        rs.getInt("attempt_count"), rs.getString("last_error"), instant(rs, "updated_at"),
                        rs.getInt("retry_count")), afterId, limit);
    }

    public List<RetryAudit> history(long taskId) {
        requireTaskId(taskId);
        return jdbc.query("SELECT * FROM r2_deletion_retry_audit WHERE task_id=? ORDER BY retry_number",
                auditMapper(), taskId);
    }

    /**
     * 실패 목록의 retryCount를 함께 보내 오래된 재처리 요청을 거절한다.
     * 동일 requestId는 같은 결과를 반환하며, 외부 파일 삭제는 기존 작업자가 담당한다.
     */
    public RetryAudit retryFailed(long taskId, int expectedRetryCount, String requestId,
                                 String operator, String reason) {
        requireTaskId(taskId);
        if (expectedRetryCount < 0 || expectedRetryCount == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid retry count");
        if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId))
            throw new IllegalArgumentException("Canonical UUID requestId required");
        String checkedOperator = checkedText(operator, 128);
        String checkedReason = checkedText(reason, 500);
        // 별도 JDBC 트랜잭션이다. 사진 삭제 등 다른 트랜잭션 안에서 호출하지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run recovery outside an existing transaction");
        return Objects.requireNonNull(transaction.execute(status -> {
            var locked = jdbc.query("SELECT id, status, attempt_count, last_error, updated_at "
                            + "FROM r2_deletion_task WHERE id=? FOR UPDATE",
                    (rs, row) -> new LockedTask(rs.getString("status"), rs.getInt("attempt_count"),
                            rs.getString("last_error"), instant(rs, "updated_at")), taskId);
            if (locked.isEmpty()) throw new IllegalArgumentException("Task not found");
            var prior = jdbc.query("SELECT * FROM r2_deletion_retry_audit WHERE request_id=?",
                    auditMapper(), requestId);
            if (!prior.isEmpty()) {
                RetryAudit audit = prior.getFirst();
                if (audit.taskId() != taskId || audit.retryNumber() != expectedRetryCount + 1
                        || !audit.operator().equals(checkedOperator) || !audit.reason().equals(checkedReason))
                    throw new IllegalStateException("requestId already used for a different request");
                return audit;
            }
            LockedTask task = locked.getFirst();
            if (!task.status().equals("FAILED")) throw new IllegalStateException("Only FAILED tasks can be retried");
            Integer currentRound = jdbc.queryForObject("SELECT COALESCE(MAX(retry_number), 0) "
                    + "FROM r2_deletion_retry_audit WHERE task_id=?", Integer.class, taskId);
            if (!Objects.equals(currentRound, expectedRetryCount))
                throw new IllegalStateException("Stale recovery request; inspect the latest failure first");
            Instant now = clock.instant();
            int changed = jdbc.update(connection -> {
                var statement = connection.prepareStatement("UPDATE r2_deletion_task SET status='PENDING', "
                        + "attempt_count=0, available_at=?, updated_at=?, lease_token=NULL, "
                        + "last_error=NULL, completed_at=NULL WHERE id=? AND status='FAILED'");
                // 작업 컬럼은 H2에서 TIMESTAMP WITH TIME ZONE, MySQL에서는 UTC DATETIME이다.
                // OffsetDateTime으로 실제 시점을 전달해 JVM 로컬 시간대에 따른 이동을 막는다.
                statement.setObject(1, now.atOffset(ZoneOffset.UTC));
                statement.setObject(2, now.atOffset(ZoneOffset.UTC));
                statement.setLong(3, taskId);
                return statement;
            });
            if (changed != 1) throw new IllegalStateException("Task state changed");
            // INSERT 실패 시 앞의 UPDATE도 함께 롤백한다. 실패 정보는 작업을 초기화하기 전에 읽었다.
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO r2_deletion_retry_audit "
                        + "(request_id, task_id, retry_number, operator_name, reason, previous_attempt_count, "
                        + "previous_error, failed_at, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                statement.setString(1, requestId);
                statement.setLong(2, taskId);
                statement.setInt(3, expectedRetryCount + 1);
                statement.setString(4, checkedOperator);
                statement.setString(5, checkedReason);
                statement.setInt(6, task.attempts());
                statement.setString(7, task.error());
                statement.setTimestamp(8, Timestamp.from(task.failedAt()), utc());
                statement.setTimestamp(9, Timestamp.from(now), utc());
                return statement;
            });
            return jdbc.queryForObject("SELECT * FROM r2_deletion_retry_audit WHERE request_id=?",
                    auditMapper(), requestId);
        }));
    }

    private static void requireTaskId(long id) {
        if (id < 1) throw new IllegalArgumentException("Positive taskId required");
    }

    private static String checkedText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max
                || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Non-blank single-line operator/reason required");
        return text.strip();
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column, utc());
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static RowMapper<RetryAudit> auditMapper() {
        return (rs, row) -> new RetryAudit(rs.getLong("id"), rs.getString("request_id"), rs.getLong("task_id"),
                rs.getInt("retry_number"), rs.getString("operator_name"), rs.getString("reason"),
                rs.getInt("previous_attempt_count"), rs.getString("previous_error"),
                instant(rs, "failed_at"), instant(rs, "requested_at"));
    }

    private record LockedTask(String status, int attempts, String error, Instant failedAt) { }
    public record FailureMarker(long taskId, Instant updatedAt) { }
    public record Summary(long pending, long processing, long completed, long failed, FailureMarker latestFailure) { }
    public record FailedTask(long taskId, String fileUrl, int attempts, String error, Instant failedAt, int retryCount) { }
    public record RetryAudit(long id, String requestId, long taskId, int retryNumber, String operator,
                             String reason, int previousAttempts, String previousError,
                             Instant failedAt, Instant requestedAt) { }
}
