package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

// 여행 Repository

public interface TravelRepository extends JpaRepository<Travel, Long> {

    // ID로 여행 조회
    @EntityGraph(attributePaths = {"user"})
    Optional<Travel> findById(Long id);

    // 여행 목록 조회
    List<Travel> findAllByUserOrderByIdDesc(User user);
}