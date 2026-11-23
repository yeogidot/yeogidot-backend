-- 운영의 ddl-auto=validate에 맞춰 앱 배포 전에 적용한다.
ALTER TABLE photo
    ADD COLUMN thumbnail_path VARCHAR(2048) NULL,
    ADD COLUMN thumbnail_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN thumbnail_retry_at DATETIME(6) NULL,
    ADD INDEX idx_photo_thumbnail_pending (thumbnail_attempts, thumbnail_retry_at, photo_id);
