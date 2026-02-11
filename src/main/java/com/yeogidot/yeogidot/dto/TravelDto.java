package com.yeogidot.yeogidot.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

//여행 API 관련 데이터 전송 객체

public class TravelDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Info {
        private Long travelId;
        private String title;
        private String trvRegion;
        private LocalDate startDate;
        private LocalDate endDate;
        private String representativeImageUrl; // 대표 사진 URL
    }

    // ===== Request 객체 =====
    // 여행 생성 요청
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String title;
        private String trvRegion;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<Long> photoIds;
        private Long representativePhotoId;
    }

    // 여행 로그 생성/수정 요청
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogRequest {
        private String content;
    }

    // 사진 댓글 생성/수정 요청
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentRequest {
        private String content;
    }

    // 여행 날짜 수동 추가 요청
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddDayRequest {
        private LocalDate date;
    }

    // ===== Response 객체 =====
    // 여행 상세 조회 응답
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailResponse {
        private Long travelId;
        private String title;
        private String trvRegion; // 추가
        private Long representativePhotoId;
        private String shareUrl;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<TravelDayDetail> days;
    }

    // 일차별 상세 정보 응답
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayDetailResponse {
        private Long dayId;
        private Integer dayNumber;
        private LocalDate date;
        private String dayRegion;
    }

    // 일차별 상세 구조
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TravelDayDetail {
        private Long dayId;
        private Integer dayNumber;
        private LocalDate date;
        private String dayRegion;
        private List<PhotoDetail> photos;
        private DiaryDetail diary;
    }

    // 사진 상세 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoDetail {
        private Long photoId;
        private String url;
        private LocalDateTime takenAt;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String region;  // 지역 정보 (예: "부산광역시 부산진구")
        private List<CommentDetail> comments; // 추가
    }

    // 댓글 상세 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentDetail {
        private Long commentId;
        private String content;
        private LocalDateTime createdAt;
    }

    // 일기 상세 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiaryDetail {
        private Long logId;
        private String content;
        private LocalDateTime logCreated;
    }

    // 공유 URL 응답 객체
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShareUrlResponse {
        private Long travelId;
        private String shareUrl;
    }
}