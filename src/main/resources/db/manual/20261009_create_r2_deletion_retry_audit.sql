-- MySQL 8. 운영자가 대상 DB를 확인한 뒤 별도로 적용한다. 기존 작업/사진 테이블은 변경하지 않는다.
-- 재처리 기능 사용 전에 필요하다. 감사 기록은 자동 삭제하지 않는다.
CREATE TABLE r2_deletion_retry_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    task_id BIGINT NOT NULL,
    retry_number INT NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    previous_attempt_count INT NOT NULL,
    previous_error VARCHAR(255) NULL,
    failed_at DATETIME(6) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_r2_retry_request UNIQUE (request_id),
    CONSTRAINT uk_r2_retry_round UNIQUE (task_id, retry_number),
    CONSTRAINT chk_r2_retry_round CHECK (retry_number > 0),
    CONSTRAINT chk_r2_retry_attempts CHECK (previous_attempt_count >= 0)
);
