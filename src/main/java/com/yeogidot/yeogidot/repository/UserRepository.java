package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

// 사용자 Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 이메일로 사용자 조회
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}