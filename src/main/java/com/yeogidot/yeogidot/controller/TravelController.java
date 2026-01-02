package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.TravelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

//여행 관련 API 요청을 처리하는 컨트롤러

@RestController
@RequestMapping("/api/v1/travels")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;
    private final UserRepository userRepository;

    // 여행 목록 조회
    @GetMapping
    public ResponseEntity<List<TravelDto.Info>> getMyTravels() {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getMyTravels(user));
    }

    // 여행 생성
    @PostMapping
    public ResponseEntity<Long> createTravel(@RequestBody TravelDto.CreateRequest request) {
        Long travelId = travelService.createTravel(request);
        return ResponseEntity.ok(travelId);
    }

    // 여행 상세 조회
    @GetMapping("/{travelId}")
    public ResponseEntity<TravelDto.DetailResponse> getTravel(@PathVariable Long travelId) {
        return ResponseEntity.ok(travelService.getTravelDetail(travelId));
    }

    // 여행 삭제
    @DeleteMapping("/{travelId}")
    public ResponseEntity<Void> deleteTravel(@PathVariable Long travelId) {
        User user = getCurrentUser();
        travelService.deleteTravel(travelId, user);
        return ResponseEntity.noContent().build();
    }

    // 여행 일차 상세 조회
    @GetMapping("/{travelId}/days/{dayNumber}")
    public ResponseEntity<TravelDto.DayDetailResponse> getTravelDay(
            @PathVariable Long travelId,
            @PathVariable Integer dayNumber) {
        return ResponseEntity.ok(travelService.getTravelDayDetail(travelId, dayNumber));
    }

    // 여행 일차 삭제
    @DeleteMapping("/api/v1/days/{dayId}")
    public ResponseEntity<Void> deleteTravelDay(@PathVariable Long dayId) {
        travelService.deleteTravelDay(dayId);
        return ResponseEntity.ok().build();
    }

    // 여행 로그 생성
    @PostMapping("/api/v1/days/{dayId}/logs")
    public ResponseEntity<Void> createLog(
            @PathVariable Long dayId,
            @RequestBody TravelDto.LogRequest request) {
        travelService.createTravelLog(dayId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 여행 로그 수정
    @PutMapping("/api/v1/logs/{logId}")
    public ResponseEntity<Void> updateLog(
            @PathVariable Long logId,
            @RequestBody TravelDto.LogRequest request) {
        travelService.updateTravelLog(logId, request);
        return ResponseEntity.ok().build();
    }

    // 현재 로그인 유저
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));
    }

    // 여행 공유 URL 조회
    @GetMapping("/{travelId}/share")
    public ResponseEntity<?> getShareUrl(@PathVariable Long travelId) {
        User user = getCurrentUser();
        TravelDto.ShareUrlResponse response = travelService.getOrCreateShareUrl(travelId, user);

        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "공유 URL을 조회했습니다.",
                "data", response
        ));
    }
}