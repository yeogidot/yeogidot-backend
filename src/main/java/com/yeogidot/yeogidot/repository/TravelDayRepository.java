package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.TravelDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TravelDayRepository extends JpaRepository<TravelDay, Long> {
    // Service에서 호출하는 커스텀 메소드 정의
    Optional<TravelDay> findByTravelIdAndDayNumber(Long travelId, Integer dayNumber);
    List<TravelDay> findByTravelId(Long travelId); // 추가
    
    // 사진까지 함께 로드
    @Query("SELECT td FROM TravelDay td LEFT JOIN FETCH td.photos WHERE td.id = :dayId")
    Optional<TravelDay> findByIdWithPhotos(@Param("dayId") Long dayId);

}