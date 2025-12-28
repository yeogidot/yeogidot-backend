package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

/**
 * [사진 Repository]
 * - Photo 엔티티의 DB 접근을 담당합니다.
 * - JpaRepository<Entity타입, PK타입>을 상속받아 기본적인 CRUD 메서드를 자동으로 제공받습니다.
 */
public interface PhotoRepository extends JpaRepository<Photo, Long> {

    // [지도 조회용 쿼리]
    // 내(UserId)가 쓴 여행(Travel) -> 일차(TravelDay) -> 사진(Photo) 순으로 찾기
    @Query("SELECT p FROM Photo p " +
            "JOIN p.travelDay d " +
            "JOIN d.travel t " +
            "WHERE t.user.userId = :userId " +
            "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL")
    List<Photo> findAllByUserId(@Param("userId") Long userId);
}