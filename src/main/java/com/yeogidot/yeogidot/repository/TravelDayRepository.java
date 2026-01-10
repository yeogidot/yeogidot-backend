package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.TravelDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TravelDayRepository extends JpaRepository<TravelDay, Long> {
    // Service에서 호출하는 커스텀 메소드 정의
    Optional<TravelDay> findByTravelIdAndDayNumber(Long travelId, Integer dayNumber);
}