package com.yeogidot.yeogidot.dto;

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
