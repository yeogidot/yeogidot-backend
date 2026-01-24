package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.TravelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "여행", description = "여행 생성, 조회, 삭제 및 관리 API")
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
    @Operation(
            summary = "여행 생성",
            description = "새로운 여행을 생성합니다. 여행 제목, 지역, 기간, 포함할 사진들, 대표 사진을 설정할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 생성 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = "1"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (날짜 형식 오류, 사진 ID 없음 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "error": "시작일은 종료일보다 이전이어야 합니다"
                    }
                    """
                            )
                    )
            )
    })
    @PostMapping
    public ResponseEntity<Long> createTravel(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "여행 생성 요청 데이터",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                        {
                          "title": "제주도 여행",
                          "trvRegion": "제주특별자치도",
                          "startDate": "2024-02-01",
                          "endDate": "2024-02-03",
                          "photoIds": [1, 2, 3, 4, 5],
                          "representativePhotoId": 1
                        }
                        """
                            )
                    )
            )
            @RequestBody TravelDto.CreateRequest request
    ) {
        User user = getCurrentUser();
        Long travelId = travelService.createTravel(request, user);
        return ResponseEntity.ok(travelId);
    }

    // 여행 상세 조회
    @GetMapping("/{travelId}")
    public ResponseEntity<TravelDto.DetailResponse> getTravel(@PathVariable Long travelId) {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getTravelDetail(travelId, user));
    }

    // 여행 삭제
    @Operation(
            summary = "여행 삭제",
            description = "특정 여행을 삭제합니다. 여행에 속한 일차(Day)들과 여행 로그도 함께 삭제되지만, 사진은 삭제되지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "여행 삭제 성공"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (다른 사용자의 여행)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "error": "본인의 여행만 삭제할 수 있습니다."
                    }
                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "여행을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "error": "해당 여행을 찾을 수 없습니다."
                    }
                    """
                            )
                    )
            )
    })
    @DeleteMapping("/{travelId}")
    public ResponseEntity<Void> deleteTravel(
            @Parameter(description = "삭제할 여행의 ID", required = true, example = "1")
            @PathVariable Long travelId
    ) {
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