package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 여행 정보 수정 요청 DTO (PATCH)
 * - null이 아닌 필드만 수정됨
 */
@Data
@Schema(description = "여행 정보 수정 요청")
public class TravelUpdateRequest {
    
    @Schema(description = "여행 제목", example = "제주도 여행", nullable = true)
    private String title;
    
    @Schema(description = "대표 사진 ID (null로 설정 가능)", example = "123", nullable = true)
    private Long representativePhotoId;
}
