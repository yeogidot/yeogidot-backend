package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/** 사진과 독립적으로 남아야 하므로 Photo/Travel에 대한 FK나 cascade를 두지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "r2_deletion_task", indexes = {
        @Index(name = "idx_r2_deletion_ready", columnList = "status,available_at,id")
})
public class R2DeletionTask {
    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_url", nullable = false, length = 2048)
    private String fileUrl;
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    // PENDING이면 다음 시도 시각, PROCESSING이면 선점 만료 시각이다.
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "last_error", length = 255)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    public static R2DeletionTask pending(String fileUrl, Instant now) {
        if (fileUrl == null || fileUrl.isBlank() || fileUrl.length() > 2048) {
            throw new IllegalArgumentException("삭제할 파일 URL이 올바르지 않습니다.");
        }
        R2DeletionTask task = new R2DeletionTask();
        task.fileUrl = fileUrl;
        task.status = Status.PENDING;
        task.availableAt = now;
        task.createdAt = now;
        task.updatedAt = now;
        return task;
    }

    public boolean isReady(Instant now) {
        return (status == Status.PENDING || status == Status.PROCESSING)
                && !availableAt.isAfter(now);
    }

    public void claim(String token, Instant now, Instant leaseUntil) {
        status = Status.PROCESSING;
        leaseToken = token;
        attemptCount++;
        availableAt = leaseUntil;
        updatedAt = now;
    }

    public boolean isOwnedBy(String token) {
        return status == Status.PROCESSING && Objects.equals(leaseToken, token);
    }

    public void complete(Instant now) {
        status = Status.COMPLETED;
        leaseToken = null;
        lastError = null;
        completedAt = now;
        updatedAt = now;
    }

    public void retry(Instant now, Instant nextAttempt, String error) {
        status = Status.PENDING;
        leaseToken = null;
        availableAt = nextAttempt;
        lastError = error;
        updatedAt = now;
    }

    public void fail(Instant now, String error) {
        status = Status.FAILED;
        leaseToken = null;
        lastError = error;
        updatedAt = now;
    }
}
