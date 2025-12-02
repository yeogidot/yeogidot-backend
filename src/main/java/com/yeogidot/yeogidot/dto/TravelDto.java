package com.yeogidot.yeogidot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 여행 API 관련 데이터 전송 객체(DTO) 모음입니다.
 * - Request(요청)와 Response(응답) 클래스를 내부 static 클래스로 관리합니다.
 */
public class TravelDto {

    // ================= Request DTOs (요청) =================

    /**
     * 여행 생성 요청 DTO
     * - 클라이언트가 여행을 처음 만들 때 보내는 데이터입니다.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String title;       // 여행 제목
        private LocalDate startDate; // 시작일
        private LocalDate endDate;   // 종료일
        // 초기 생성 시 사진 목록 (현재는 사용하지 않으나 확장성을 위해 유지)
        private List<Long> photoIds;
        private Long representativePhotoId; // 대표 사진 ID
    }

    // ================= Response DTOs (응답) =================

    /**
     * 여행 상세 조회 응답 DTO
     * - 여행의 기본 정보와 하위 일차(Day) 정보를 포함합니다.
     */
    @Getter
    @Builder
    public static class DetailResponse {
        private Long travelId;
        private String title;
        private Long representativePhotoId;
        private String shareUrl;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<TravelDayDetail> days; // N일차 리스트
    }

    /**
     * 일차(Day)별 상세 정보
     * - 예: 1일차, 2일차 등 해당 날짜의 지역, 사진, 일기 정보
     */
    @Getter
    @Builder
    public static class TravelDayDetail {
        private Long dayId;
        private Integer dayNumber; // 1, 2, 3... (N일차)
        private LocalDate date;    // 실제 날짜
        private String dayRegion;  // 그날의 대표 지역
        private List<PhotoDetail> photos; // 그날 찍은 사진들
        private DiaryDetail diary;        // 그날의 일기
    }

    /**
     * 사진 상세 정보
     * - 지도에 표시하기 위한 좌표(위도/경도) 정보를 포함합니다.
     */
    @Getter
    @Builder
    public static class PhotoDetail {
        private Long photoId;
        private String url;        // 사진 접근 URL
        private LocalDateTime takenAt; // 촬영 시간
        private BigDecimal latitude;   // 위도
        private BigDecimal longitude;  // 경도
    }

    /**
     * 일기 상세 정보
     */
    @Getter
    @Builder
    public static class DiaryDetail {
        private Long logId;
        private String content;
        private LocalDateTime logCreated;
    }
}