package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.TravelDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    // Photo 엔티티에 user 필드가 있으므로 직접 조회 (TravelDay JOIN 불필요)
    @Query("SELECT p FROM Photo p " +
            "WHERE p.user.id = :userId " +
            "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL")
    List<Photo> findAllByUserId(@Param("userId") Long userId);
    List<Photo> findByTravelDay(TravelDay travelDay); // 추가

}