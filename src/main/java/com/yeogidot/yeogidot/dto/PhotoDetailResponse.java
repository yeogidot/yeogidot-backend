package com.yeogidot.yeogidot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 단일 사진 상세 정보 응답 객체

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
    private String region;         // 지역 정보 (예: "부산광역시 부산진구")
}