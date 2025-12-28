package com.yeogidot.yeogidot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [DTO] 단일 사진 상세 정보 응답 객체
 * - Photo 엔티티의 정보를 클라이언트에 전달할 때 사용됩니다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoDetailResponse {
    private Long photoId;
    private String fileUrl;      // 파일 경로 또는 URL
    private LocalDateTime takenAt; // 촬영 일시
    private BigDecimal latitude;   // 위도
    private BigDecimal longitude;  // 경도
}