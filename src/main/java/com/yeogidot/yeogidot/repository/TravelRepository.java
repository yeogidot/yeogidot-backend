package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Travel;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * [여행 Repository]
 * - Travel 엔티티의 DB 접근(CRUD)을 담당합니다.
 */
public interface TravelRepository extends JpaRepository<Travel, Long> {

    /**
     * ID로 여행 조회
     * - @EntityGraph: 여행을 조회할 때 'user'(작성자) 정보도 쿼리 한 번에 미리 가져옵니다.
     * - 목적: 나중에 travel.getUser()를 호출할 때 추가 쿼리가 나가는 문제(N+1) 방지
     */
    @EntityGraph(attributePaths = {"user"})
    Optional<Travel> findById(Long id);
}