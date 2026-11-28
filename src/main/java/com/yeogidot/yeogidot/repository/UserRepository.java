package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.ResourceNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 사용자 Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 같은 소유자의 여행이나 사진을 변경하기 전, 첫 DB 조회로 실행한다.
    // 출발·도착 일차가 서로 다른 여행에 있어도 같은 행을 잠근다.
    default void lockForTravelMutation(Long userId) {
        findForTravelMutation(userId)
                .orElseThrow(() -> new ResourceNotFoundException("존재하지 않는 사용자입니다."));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findForTravelMutation(@Param("userId") Long userId);

    // 이메일로 사용자 조회
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByNicknameKey(String nicknameKey);
    boolean existsByNicknameKeyAndIdNot(String nicknameKey, Long userId);
}
