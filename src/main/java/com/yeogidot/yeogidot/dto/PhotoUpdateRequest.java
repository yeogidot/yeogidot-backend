package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 사진 정보 수정 요청 DTO (PATCH)
 * - null이 아닌 필드만 수정됨
 */
@Data
@Schema(description = "사진 정보 수정 요청")
public class PhotoUpdateRequest {
    
    @Schema(description = "촬영 시간 (ISO 8601 형식)", example = "2025-01-15T14:30:00", nullable = true)
    private LocalDateTime takenAt;
    
    @Schema(description = "이동할 여행 일차 ID", example = "5", nullable = true)
    private Long dayId;
    
    @Schema(description = "위도", example = "37.5665", nullable = true)
    private Double latitude;
    
    @Schema(description = "경도", example = "126.9780", nullable = true)
    private Double longitude;
}
