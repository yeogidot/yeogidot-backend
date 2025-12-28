package com.yeogidot.yeogidot.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDate;

@Getter
@Builder
public class TravelDto {
    private Long travelId;
    private String title;
    private String trvRegion;
    private LocalDate startDate;
    private LocalDate endDate;
    // 대표 사진 URL (없으면 null)
    private String representativeImageUrl;
}