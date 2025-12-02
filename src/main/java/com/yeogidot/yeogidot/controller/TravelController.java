package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.service.TravelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 여행(Travel) 관련 API 요청을 처리하는 컨트롤러입니다.
 * - 여행 생성, 조회, 수정, 삭제 등의 HTTP 요청을 받습니다.
 */
@RestController
@RequestMapping("/api/v1/travels")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;

    /**
     * [GET] 여행 상세 조회
     * URL: /api/v1/travels/{travelId}
     * - 특정 여행의 상세 정보(일차별 스케줄, 사진 포함)를 조회합니다.
     */
    @GetMapping("/{travelId}")
    public ResponseEntity<TravelDto.DetailResponse> getTravel(@PathVariable Long travelId) {
        return ResponseEntity.ok(travelService.getTravelDetail(travelId));
    }

    /**
     * [POST] 여행 생성
     * URL: /api/v1/travels
     * - 새로운 여행 기록을 생성합니다.
     * - 반환값: 생성된 여행의 ID (travelId)
     */
    @PostMapping
    public ResponseEntity<Long> createTravel(@RequestBody TravelDto.CreateRequest request) {
        Long travelId = travelService.createTravel(request);
        return ResponseEntity.ok(travelId);
    }
}