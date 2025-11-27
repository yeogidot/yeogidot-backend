package com.yeogidot.yeogidot.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PhotoDetailResponse {
    private Long photoId;
    private String title;
    private String description;
    private String imageUrl;
    private String uploadedDate;
}
