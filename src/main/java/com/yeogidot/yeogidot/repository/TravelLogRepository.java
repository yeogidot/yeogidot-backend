package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.TravelLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TravelLogRepository extends JpaRepository<TravelLog, Long> {
    
    @Modifying
    @Query("DELETE FROM TravelLog tl WHERE tl.travelDay = :travelDay")
    void deleteByTravelDay(@Param("travelDay") TravelDay travelDay);
}