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
@RequestMapping("/api/travels")
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
        User user = getCurrentUser(); // 현재 로그인한 사람
        Long travelId = travelService.createTravel(request, user); // 유저 정보 전달
        return ResponseEntity.ok(travelId);
    }

    // 여행 상세 조회
    @GetMapping("/{travelId}")
    public ResponseEntity<TravelDto.DetailResponse> getTravel(@PathVariable Long travelId) {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getTravelDetail(travelId, user));
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
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getTravelDayDetail(travelId, dayNumber, user));
    }

    // 여행 일차 삭제
    @DeleteMapping("/days/{dayId}")
    public ResponseEntity<Void> deleteTravelDay(@PathVariable Long dayId) {
        User user = getCurrentUser();
        travelService.deleteTravelDay(dayId, user);
        return ResponseEntity.ok().build();
    }

    // 여행 일차 수동 추가
    @PostMapping("/{travelId}/days")
    public ResponseEntity<Map<String, Object>> addTravelDay(
            @PathVariable Long travelId,
            @RequestBody TravelDto.AddDayRequest request) {
        User user = getCurrentUser();
        Long dayId = travelService.addTravelDay(travelId, request, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "날짜가 추가되었습니다.",
                "dayId", dayId
        ));
    }

    // 여행 로그 생성
    @PostMapping("/days/{dayId}/logs")
    public ResponseEntity<Void> createLog(
            @PathVariable Long dayId,
            @RequestBody TravelDto.LogRequest request) {
        User user = getCurrentUser();
        travelService.createTravelLog(dayId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 여행 로그 수정
    @PutMapping("/logs/{logId}")
    public ResponseEntity<Void> updateLog(
            @PathVariable Long logId,
            @RequestBody TravelDto.LogRequest request) {
        User user = getCurrentUser();
        travelService.updateTravelLog(logId, request, user);
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

    // 대표 사진 수정 API
    @PutMapping("/{travelId}/representative-photo")
    public ResponseEntity<?> updateRepresentativePhoto(
            @PathVariable Long travelId,
            @RequestBody Map<String, Long> request) {
        try {
            User user = getCurrentUser();
            Long photoId = request.get("representativePhotoId");

            travelService.updateRepresentativePhoto(travelId, photoId, user);

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "대표 사진이 수정되었습니다."
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", 404,
                    "error", "NOT_FOUND",
                    "message", e.getMessage()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", 403,
                    "error", "FORBIDDEN",
                    "message", e.getMessage()
            ));
        }
    }
}