package com.yeogidot.yeogidot.dto;

import lombok.*;
import java.math.BigDecimal;

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
}
