package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * [사용자 Repository]
 * - User 엔티티의 DB 접근을 담당합니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 이메일로 사용자 조회
     * - 로그인이나 중복 가입 확인 시 사용됩니다.
     * - 반환형이 Optional이므로 결과가 없을 때 null 처리가 용이합니다.
     */
    Optional<User> findByEmail(String email);
}