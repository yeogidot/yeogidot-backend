package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 기본 PhotoDto (기존 코드 호환성 유지)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoDto {
    private Long photoId;
    private BigDecimal latitude;
    private BigDecimal longitude;
    @Schema(
            description = "지도 마커에 표시할 이미지 URL입니다. S 이미지는 가로세로 비율을 유지하고 긴 변을 최대 192px로 줄입니다. "
                    + "긴 변이 192px 이하면 확대하지 않습니다. S 이미지가 아직 없으면 원본 이미지 URL을 반환합니다.",
            example = "https://storage.example.com/photo.jpg.map-s.jpg"
    )
    private String thumbnailUrl;

    // 사진 목록 조회용 상세 DTO
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {
        private Long id;
        private String filePath;
        private String originalName;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private LocalDateTime takenAt;
        private String url;
        private String region;  // 지역 정보 (예: "부산광역시 부산진구")
        private UserInfo user;
        private LocalDateTime createdDate;
        private LocalDateTime modifiedDate;

        @Getter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class UserInfo {
            private Long id;
            private String email;
        }
    }
}
