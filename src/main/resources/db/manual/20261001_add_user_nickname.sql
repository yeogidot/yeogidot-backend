-- MySQL 8.0: 애플리케이션 배포 전에 대상 DB를 확인하고 1회 실행한다.
-- 기존 회원/구버전 가입은 두 컬럼이 NULL인 미설정 상태로 유지한다.
-- nickname_key는 서버가 NFC 정규화 + 앞뒤 공백 제거 후 대소문자를 유지해 생성한다.
-- 대소문자 및 서로 다른 한글을 구분하도록 nickname_key에 binary 비교를 사용한다.
SELECT DATABASE();

ALTER TABLE users
    ADD COLUMN nickname VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN nickname_key VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT uk_users_nickname_key UNIQUE (nickname_key);

SHOW CREATE TABLE users;
