package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 로그인 시도 제한 서비스 (Redis 기반)
 * - 5회 실패 시 5분간 잠금
 * - 로그인 성공 시 시도 횟수 초기화
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String KEY_PREFIX = "login_attempt:";
    private static final int MAX_ATTEMPTS = 5;      // 최대 시도 횟수
    private static final long LOCK_DURATION = 5;    // 잠금 시간 (분)

    /**
     * 로그인 실패 처리
     * - 실패 횟수 증가
     * - 5회 초과 시 5분간 잠금
     */
    public void loginFailed(String email) {
        String key = KEY_PREFIX + email;
        Long attempts = redisTemplate.opsForValue().increment(key);

        if (attempts == 1) {
            // 첫 실패 시 TTL 설정 (5분)
            redisTemplate.expire(key, LOCK_DURATION, TimeUnit.MINUTES);
        }

        log.warn("🔐 로그인 실패: {} - {}회 시도", email, attempts);
    }

    /**
     * 로그인 성공 처리
     * - 시도 횟수 초기화
     */
    public void loginSucceeded(String email) {
        String key = KEY_PREFIX + email;
        redisTemplate.delete(key);
        log.info("✅ 로그인 성공: {} - 시도 횟수 초기화", email);
    }

    /**
     * 계정 잠금 여부 확인
     */
    public boolean isBlocked(String email) {
        String key = KEY_PREFIX + email;
        String attempts = redisTemplate.opsForValue().get(key);

        if (attempts == null) return false;

        boolean blocked = Integer.parseInt(attempts) >= MAX_ATTEMPTS;
        if (blocked) {
            log.warn("🚫 로그인 차단: {} - {}회 초과", email, attempts);
        }
        return blocked;
    }

    /**
     * 남은 시도 횟수 반환
     */
    public int getRemainingAttempts(String email) {
        String key = KEY_PREFIX + email;
        String attempts = redisTemplate.opsForValue().get(key);
        if (attempts == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - Integer.parseInt(attempts));
    }
}
