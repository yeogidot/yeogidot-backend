package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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

    // 사진 댓글 내용 요청
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentRequest {
        @NotBlank(message = "댓글 내용을 입력해주세요.")
        @Schema(description = "댓글 내용", example = "사진이 멋져요!")
        private String content;
    }

    // 사진 댓글 등록 요청
    @Getter
    @NoArgsConstructor
    public static class CommentCreateRequest extends CommentRequest {
        @Schema(description = "다른 사용자의 사진에 댓글을 작성할 때 필요한 여행 공유 토큰입니다. 공유 URL의 /share/ 뒤에 있는 값입니다.",
                example = "abc-123-def")
        private String shareToken;

        public CommentCreateRequest(String content, String shareToken) {
            super(content);
            this.shareToken = shareToken;
        }
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
        @Schema(description = "댓글 ID. 댓글 수정·삭제에 사용합니다.", example = "1")
        private Long commentId;
        private String content;
        private LocalDateTime createdAt;
        @Schema(description = "작성자 계정 ID. GET /api/auth/me의 userId와 비교해 내 댓글을 구분할 수 있습니다.", example = "12")
        private Long writerId;
        @Schema(description = "댓글에 표시할 작성자의 닉네임. 닉네임을 설정하지 않은 계정은 null입니다.",
                example = "바다여행", nullable = true)
        private String nickname;
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
