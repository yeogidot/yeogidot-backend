package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.dto.TravelUpdateRequest;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.TravelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(
            summary = "여행 목록 조회",
            description = "현재 로그인한 사용자의 모든 여행 목록을 조회합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            [
                                              {
                                                "travelId": 1,
                                                "title": "제주도 여행",
                                                "trvRegion": "제주특별자치도",
                                                "startDate": "2025-01-15",
                                                "endDate": "2025-01-18",
                                                "representativeImageUrl": "https://storage.googleapis.com/bucket/photo1.jpg"
                                              },
                                              {
                                                "travelId": 2,
                                                "title": "부산 여행",
                                                "trvRegion": "부산광역시",
                                                "startDate": "2025-02-01",
                                                "endDate": "2025-02-03",
                                                "representativeImageUrl": "https://storage.googleapis.com/bucket/photo5.jpg"
                                              }
                                            ]
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping
    public ResponseEntity<List<TravelDto.Info>> getMyTravels() {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getMyTravels(user));
    }

    // 여행 생성
    @Operation(
            summary = "여행 생성",
            description = "새로운 여행을 생성합니다. 사진들의 촬영 날짜를 기반으로 여행 기간이 자동 설정되고, 위치 정보를 기반으로 지역명이 자동 설정됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 생성 성공 (생성된 여행 ID 반환)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = "123"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (사진 없음, 날짜 정보 없음 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "최소 1장 이상의 사진을 선택해주세요."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
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
    @Operation(
            summary = "특정 여행 상세 조회",
            description = "여행 ID로 여행의 모든 일차, 사진, 로그, 댓글 정보를 포함한 상세 정보를 조회합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 상세 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                                          "travelId": 1,
                                                   "title": "제주도 여행",
                                                   "trvRegion": "제주특별자치도",
                                                   "representativePhotoId": 3,
                                                   "shareUrl": "https://travel.vercel.app/share/abc-123-def",
                                                   "startDate": "2025-01-15",
                                                   "endDate": "2025-01-18",
                                                   "days": [
                                                     {
                                                       "dayId": 115,
                                                       "dayNumber": 1,
                                                       "date": "2025-01-15",
                                                       "dayRegion": "제주시",
                                                       "photos": [
                                                         {
                                                           "photoId": 1,
                                                           "url": "https://storage.googleapis.com/bucket/photo1.jpg",
                                                           "takenAt": "2025-01-15T10:30:00Z",
                                                           "latitude": 33.5145,
                                                           "longitude": 126.5294,
                                                           "comments": [
                                                             {
                                                               "commentId": 1,
                                                               "content": "정말 아름다운 풍경이네요!",
                                                               "createdAt": "2025-01-15T14:20:00Z"
                                                             },
                                                             {
                                                               "commentId": 2,
                                                               "content": "다음에 또 가고 싶어요",
                                                               "createdAt": "2025-01-15T15:10:00Z"
                                                             }
                                                           ]
                                                         },
                                                         {
                                                           "photoId": 2,
                                                           "url": "https://storage.googleapis.com/bucket/photo2.jpg",
                                                           "takenAt": "2025-01-15T14:45:00Z",
                                                           "latitude": 33.5201,
                                                           "longitude": 126.5333,
                                                           "comments": []
                                                         }
                                                       ],
                                                       "diary": {
                                                         "logId": 5,
                                                         "content": "첫날은 성산일출봉에 다녀왔습니다. 날씨가 정말 좋았어요!",
                                                         "logCreated": "2025-01-15T20:00:00Z"
                                                       }
                                                     },
                                                     {
                                                       "dayId": 116,
                                                       "dayNumber": 2,
                                                       "date": "2025-01-16",
                                                       "dayRegion": "서귀포시",
                                                       "photos": [
                                                         {
                                                           "photoId": 3,
                                                           "url": "https://storage.googleapis.com/bucket/photo3.jpg",
                                                           "takenAt": "2025-01-16T09:15:00Z",
                                                           "latitude": 33.2541,
                                                           "longitude": 126.5604,
                                                           "comments": []
                                                         }
                                                       ],
                                                       "diary": null
                                                     }
                                                   ]
                                                 }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 만료)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (다른 사용자의 여행)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "해당 여행을 조회할 권한이 없습니다."
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
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "여행 기록을 찾을 수 없습니다. ID=999"
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/{travelId}")
    public ResponseEntity<TravelDto.DetailResponse> getTravel(
            @Parameter(description = "조회할 여행의 ID", required = true, example = "1")
            @PathVariable Long travelId
    ) {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getTravelDetail(travelId, user));
    }

    // 여행 삭제
    @Operation(
            summary = "여행 삭제",
            description = "특정 여행을 삭제합니다. 여행에 속한 일차(Day)들과 여행 로그, GCS의 사진 파일이 모두 삭제됩니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "여행 삭제 성공 (응답 본문 없음)"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 만료)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (다른 사용자의 여행)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "삭제 권한이 없습니다."
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
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "존재하지 않는 여행입니다."
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
    @Operation(
            summary = "여행 일차 상세 조회",
            description = "특정 여행의 특정 일차(Day) 상세 정보를 조회합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 일차 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "dayId": 115,
                                              "dayNumber": 1,
                                              "date": "2025-01-15",
                                              "dayRegion": "제주시"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 만료)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "조회 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "일차를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "해당 일차 정보를 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/{travelId}/days/{dayNumber}")
    public ResponseEntity<TravelDto.DayDetailResponse> getTravelDay(
            @Parameter(description = "여행 ID", required = true, example = "1")
            @PathVariable Long travelId,
            @Parameter(description = "조회할 일차 번호", required = true, example = "1")
            @PathVariable Integer dayNumber
    ) {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getTravelDayDetail(travelId, dayNumber, user));
    }

    // 여행 일차 삭제
    @Operation(
            summary = "여행 일차 삭제",
            description = "특정 여행 일차(Day)를 삭제합니다. 해당 일차에 속한 사진(GCS 파일 포함)과 여행 로그가 모두 삭제됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 일차 삭제 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 만료)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "삭제 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "일차를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "존재하지 않는 일차입니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping("/days/{dayId}")
    public ResponseEntity<Void> deleteTravelDay(
            @Parameter(description = "dayId (데이터베이스 고유 ID)", required = true, example = "115")
            @PathVariable Long dayId
    ) {
        User user = getCurrentUser();
        travelService.deleteTravelDay(dayId, user);
        return ResponseEntity.ok().build();
    }

    // 여행 일차 수동 추가
    @Operation(
            summary = "여행 일차 수동 추가",
            description = "기존 여행에 새로운 일차를 수동으로 추가합니다. 날짜 순서에 맞게 자동으로 dayNumber가 배정됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 일차 추가 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "날짜가 추가되었습니다.",
                                              "dayId": 125
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (이미 존재하는 날짜 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "해당 날짜는 이미 존재합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping("/{travelId}/days")
    public ResponseEntity<Map<String, Object>> addTravelDay(
            @Parameter(description = "여행 ID", required = true, example = "1")
            @PathVariable Long travelId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추가할 일차 정보",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "date": "2025-01-20"
                                            }
                                            """
                            )
                    )
            )
            @RequestBody TravelDto.AddDayRequest request
    ) {
        User user = getCurrentUser();
        Long dayId = travelService.addTravelDay(travelId, request, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "날짜가 추가되었습니다.",
                "dayId", dayId
        ));
    }

    // 여행 일차에 사진 추가
    @Operation(
            summary = "여행 일차에 사진 추가",
            description = "특정 여행 일차에 기존 사진들을 추가합니다. 여러 사진을 한 번에 추가할 수 있으며, 사진의 위치 정보를 기반으로 일차의 지역명이 자동으로 설정됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 추가 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "3개의 사진이 추가되었습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (다른 사용자의 사진)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "본인의 사진만 추가할 수 있습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "일차 또는 사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "ID 5 사진을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping("/days/{dayId}/photos")
    public ResponseEntity<?> addPhotosToDay(
            @Parameter(description = "dayId (데이터베이스 고유 ID)", required = true, example = "115")
            @PathVariable Long dayId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추가할 사진 ID 목록",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "photoIds": [1, 2, 3]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody Map<String, List<Long>> request
    ) {
        User user = getCurrentUser();
        List<Long> photoIds = request.get("photoIds");
        int count = travelService.addPhotosToDay(dayId, photoIds, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", count + "개의 사진이 추가되었습니다."
        ));
    }

    // 여행 로그 생성
    @Operation(
            summary = "여행 로그(일기) 생성",
            description = "특정 여행 일차에 새로운 로그(일기)를 작성합니다. 하나의 일차에는 하나의 로그만 작성할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "여행 로그 생성 성공"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "권한이 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "일차를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "존재하지 않는 일차입니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping("/days/{dayId}/logs")
    public ResponseEntity<Void> createLog(
            @Parameter(description = "dayId (데이터베이스 고유 ID, dayNumber가 아님)", required = true, example = "115")
            @PathVariable Long dayId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "여행 로그 내용",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "content": "오늘은 성산일출봉에 다녀왔습니다. 일출이 정말 아름다웠어요!"
                                            }
                                            """
                            )
                    )
            )
            @RequestBody TravelDto.LogRequest request
    ) {
        User user = getCurrentUser();
        travelService.createTravelLog(dayId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 여행 로그 수정
    @Operation(
            summary = "여행 로그(일기) 수정",
            description = "기존 여행 로그의 내용을 수정합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 로그 수정 성공"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "권한이 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "로그를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "존재하지 않는 일기입니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/logs/{logId}")
    public ResponseEntity<Void> updateLog(
            @Parameter(description = "logId (데이터베이스 고유 ID)", required = true, example = "1")
            @PathVariable Long logId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 로그 내용",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "content": "수정: 오늘은 성산일출봉과 섭지코지를 다녀왔습니다. 날씨가 좋아서 더 즐거웠어요!"
                                            }
                                            """
                            )
                    )
            )
            @RequestBody TravelDto.LogRequest request
    ) {
        User user = getCurrentUser();
        travelService.updateTravelLog(logId, request, user);
        return ResponseEntity.ok().build();
    }

    // 여행 로그 삭제
    @Operation(
            summary = "여행 로그(일기) 삭제",
            description = "기존 여행 로그를 삭제합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "여행 로그 삭제 성공 (응답 본문 없음)"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "권한이 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "로그를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "존재하지 않는 일기입니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping("/logs/{logId}")
    public ResponseEntity<Void> deleteLog(
            @Parameter(description = "logId (데이터베이스 고유 ID)", required = true, example = "1")
            @PathVariable Long logId
    ) {
        User user = getCurrentUser();
        travelService.deleteTravelLog(logId, user);
        return ResponseEntity.noContent().build();
    }

    // 현재 로그인 유저
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));
    }

    // 여행 공유 URL 조회/생성
    @Operation(
            summary = "여행 공유 URL 조회/생성",
            description = "여행 공유를 위한 URL을 생성하거나 조회합니다. URL이 없으면 자동으로 생성됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "공유 URL 조회/생성 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "공유 URL을 조회했습니다.",
                                              "data": {
                                                "travelId": 1,
                                                "shareUrl": "https://travel.vercel.app/share/abc-123-def-456"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (다른 사용자의 여행)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "해당 여행에 대한 권한이 없습니다."
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
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "여행을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/{travelId}/share")
    public ResponseEntity<Map<String, Object>> getOrCreateShareUrl(
            @Parameter(description = "여행 ID", required = true, example = "1")
            @PathVariable Long travelId
    ) {
        User user = getCurrentUser();
        String shareUrl = travelService.getOrCreateShareUrl(travelId, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "공유 URL을 조회했습니다.",
                "data", Map.of(
                        "travelId", travelId,
                        "shareUrl", shareUrl
                )
        ));
    }

    // 공유 URL로 여행 조회 (인증 불필요)
    @Operation(
            summary = "공유 URL로 여행 조회",
            description = "공유 URL을 통해 여행 상세 정보를 조회합니다. 인증이 필요하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "travelId": 1,
                                              "title": "제주도 여행",
                                              "trvRegion": "제주특별자치도",
                                              "startDate": "2025-01-15",
                                              "endDate": "2025-01-18",
                                              "days": [
                                                {
                                                  "dayId": 115,
                                                  "dayNumber": 1,
                                                  "date": "2025-01-15",
                                                  "dayRegion": "제주시",
                                                  "photos": [
                                                    {
                                                      "photoId": 1,
                                                      "url": "https://storage.googleapis.com/bucket/photo1.jpg",
                                                      "takenAt": "2025-01-15T10:30:00Z",
                                                      "latitude": 33.5145,
                                                      "longitude": 126.5294
                                                    }
                                                  ],
                                                  "diary": {
                                                    "logId": 5,
                                                    "content": "첫날은 성산일출봉에 다녀왔습니다.",
                                                    "logCreated": "2025-01-15T20:00:00Z"
                                                  }
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "공유 URL을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "유효하지 않은 공유 URL입니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/share/{shareToken}")
    public ResponseEntity<TravelDto.DetailResponse> getTravelByShareUrl(
            @Parameter(description = "공유 토큰 (URL의 마지막 부분)", required = true, example = "abc-123-def-456")
            @PathVariable String shareToken
    ) {
        return ResponseEntity.ok(travelService.getTravelByShareToken(shareToken));
    }

    /**
     * 여행 정보 수정 (PATCH) - 통합 API
     */
    @Operation(
            summary = "여행 정보 수정 (통합)",
            description = "여행의 제목, 대표 사진, 사진 목록을 한 번에 수정합니다. null이 아닌 필드만 수정됩니다.\n\n" +
                    "**photoIds 사용 시 주의사항:**\n" +
                    "- photoIds를 전송하면 기존 모든 사진과 일차가 삭제되고 새로운 사진들로 완전히 교체됩니다.\n" +
                    "- 빈 일차는 자동으로 삭제됩니다.\n" +
                    "- 사진의 촬영 날짜를 기반으로 새로운 일차가 자동 생성됩니다.\n" +
                    "- 여행 날짜와 지역이 자동으로 업데이트됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "여행 수정 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "여행이 수정되었습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "여행을 수정할 권한이 없습니다."
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
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "여행이 존재하지 않습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PatchMapping("/{travelId}")
    public ResponseEntity<?> updateTravel(
            @Parameter(description = "수정할 여행의 ID", required = true, example = "1")
            @PathVariable Long travelId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 필드 (null이 아닌 필드만 수정됨)",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "제목만 수정",
                                            value = """
                                                    {
                                                      "title": "수정된 여행 제목"
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "대표 사진만 수정",
                                            value = """
                                                    {
                                                      "representativePhotoId": 123
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "사진 전체 교체 (기존 사진/일차 모두 삭제)",
                                            value = """
                                                    {
                                                      "photoIds": [4, 5, 6, 7, 8]
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "여러 필드 동시 수정",
                                            value = """
                                                    {
                                                      "title": "수정된 제주도 여행",
                                                      "photoIds": [4, 5, 6],
                                                      "representativePhotoId": 4
                                                    }
                                                    """
                                    )
                            }
                    )
            )
            @RequestBody TravelUpdateRequest request
    ) {
        try {
            User user = getCurrentUser();
            travelService.updateTravel(travelId, request, user);

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "여행이 수정되었습니다."
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
