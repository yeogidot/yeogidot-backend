package com.yeogidot.yeogidot.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사진을 특정 날짜로 이동하는 요청 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MovePhotoRequest {
    private Long dayId;
}
