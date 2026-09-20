-- MySQL 8: 배포 전에 대상 DB를 확인한 후 별도로 실행한다. 자동 실행 스크립트가 아니다.
-- 운영은 ddl-auto=validate이므로 이 테이블 없이 새 애플리케이션을 시작하면 검증이 실패한다.
CREATE TABLE r2_deletion_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_url VARCHAR(2048) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    available_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36) NULL,
    last_error VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_r2_deletion_ready (status, available_at, id),
    CONSTRAINT chk_r2_deletion_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- 수동 확인용 조회 (자동 재시도 한도 초과/영구 오류 및 오래된 미완료 작업).
-- SELECT id, status, attempt_count, last_error, available_at, created_at
-- FROM r2_deletion_task
-- WHERE status = 'FAILED'
--    OR (status IN ('PENDING', 'PROCESSING') AND available_at < UTC_TIMESTAMP() - INTERVAL 10 MINUTE)
-- ORDER BY available_at, id;
-- 완료/실패 이력은 이번 구현에서 자동 삭제하지 않는다. 보존 정책은 별도로 결정한다.
