package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

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
    
    @Schema(
        description = "여행에 포함될 사진 ID 목록 (전체 교체). 기존 사진들은 모두 삭제되고 새로운 사진들로 대체됨. 빈 일차는 자동 삭제됨.",
        example = "[1, 2, 3, 4, 5]",
        nullable = true
    )
    private List<Long> photoIds;
}
