package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.TravelDay;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    // 댓글 등록과 구버전의 단건 확인을 직렬화해 확인 중 댓글이 추가되는 것을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Photo p WHERE p.id = :photoId")
    Optional<Photo> findByIdForCommentMutation(@Param("photoId") Long photoId);

    // 지도 마커용 - 위치 정보 있는 사진만 조회
    @Query("SELECT p FROM Photo p " +
            "WHERE p.user.id = :userId " +
            "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL")
    List<Photo> findAllByUserId(@Param("userId") Long userId);

    List<Photo> findByTravelDay(TravelDay travelDay);

    List<Photo> findByTravelDayIn(List<TravelDay> travelDays);

    // 유저의 모든 사진 조회 (회원탈퇴 시 R2 파일 삭제용)
    List<Photo> findByUserId(Long userId);

    // 유저의 미분류 사진만 조회 (여행에 속하지 않은 사진, 회원탈퇴 시 DB 삭제용)
    List<Photo> findByUserIdAndTravelDayIsNull(Long userId);

    long countByTravelDayId(Long travelDayId);
}
