package com.yeogidot.yeogidot.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter @Builder
public class PhotoDto {
    private Long photoId;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String thumbnailUrl;
}