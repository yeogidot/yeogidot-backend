package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

// 여행 Repository

public interface TravelRepository extends JpaRepository<Travel, Long> {

    // ID로 여행 조회 (User와 함께)
    @EntityGraph(attributePaths = {"user"})
    Optional<Travel> findById(Long id);

    // 여행 목록 조회 (기본 - N+1 발생 가능)
    List<Travel> findAllByUserOrderByIdDesc(User user);

    // Travel과 TravelDay만 fetch (1단계)
    @Query("SELECT DISTINCT t FROM Travel t " +
           "LEFT JOIN FETCH t.travelDays " +
           "WHERE t.id = :travelId")
    Optional<Travel> findByIdWithDays(@Param("travelId") Long travelId);

    // Travel, TravelDay, Photo, Comment를 3단계로 fetch
    // 1단계: Travel + TravelDays
    @Query("SELECT DISTINCT t FROM Travel t " +
           "LEFT JOIN FETCH t.travelDays td " +
           "WHERE t.id = :travelId")
    Optional<Travel> findByIdWithDetails(@Param("travelId") Long travelId);

    // 2단계: TravelDays + Photos (별도 쿼리)
    @Query("SELECT DISTINCT td FROM TravelDay td " +
           "LEFT JOIN FETCH td.photos " +
           "WHERE td.travel.id = :travelId")
    List<TravelDay> findDaysWithPhotos(@Param("travelId") Long travelId);

    // 3단계: Photos + Comments (별도 쿼리)
    @Query("SELECT DISTINCT p FROM Photo p " +
           "LEFT JOIN FETCH p.comments " +
           "WHERE p.travelDay.travel.id = :travelId")
    List<Photo> findPhotosWithComments(@Param("travelId") Long travelId);

    // 4단계: TravelLogs (별도 쿼리)
    @Query("SELECT DISTINCT td FROM TravelDay td " +
           "LEFT JOIN FETCH td.travelLogs " +
           "WHERE td.travel.id = :travelId")
    List<TravelDay> findDaysWithLogs(@Param("travelId") Long travelId);

    // 공유 URL로 여행 조회
    Optional<Travel> findByShareUrl(String shareUrl);
}
