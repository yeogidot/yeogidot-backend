package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.MovePhotoRequest;
import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.PhotoUpdateRequest;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.PhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "사진", description = "사진 업로드, 조회, 삭제 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final UserRepository userRepository;

    /**
     * 사진 업로드
     */
    @Operation(
            summary = "사진 업로드",
            description = "여러 장의 사진을 업로드합니다. 메타데이터(위치, 촬영시간 등)를 JSON 형식으로 함께 전송합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "사진 업로드 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 201,
                                              "message": "사진 업로드 성공",
                                              "uploadedPhotos": [
                                                {
                                                  "id": 1,
                                                  "filePath": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg",
                                                  "originalName": "test.jpg",
                                                  "latitude": 37.5665,
                                                  "longitude": 126.9780,
                                                  "takenAt": "2024-01-15T14:30:00"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (파일 누락, 메타데이터 형식 오류 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "파일 누락",
                                            value = """
                                                    {
                                                      "status": 400,
                                                      "error": "MISSING_REQUIRED_PART",
                                                      "message": "업로드할 사진 파일(files)이 필요합니다.",
                                                      "detail": "필수 파라미터 'files'가 누락되었습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "메타데이터 형식 오류",
                                            value = """
                                                    {
                                                      "status": 400,
                                                      "error": "BAD_REQUEST",
                                                      "message": "메타데이터 형식이 올바르지 않습니다. JSON 배열 형식이어야 합니다. 예시: [{\\"originalName\\":\\"photo1.jpg\\",\\"takenAt\\":\\"2025-11-12T10:00:00\\",\\"latitude\\":35.1584,\\"longitude\\":129.1603}]"
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음, 만료, 형식 오류)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 401,
                                              "error": "UNAUTHORIZED",
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "파일 크기 초과",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 413,
                                              "error": "FILE_TOO_LARGE",
                                              "message": "업로드 파일 크기가 제한을 초과했습니다.",
                                              "detail": "최대 파일 크기는 10MB입니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류 (파일 업로드 실패 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 500,
                                              "error": "IO_ERROR",
                                              "message": "파일 처리 중 오류가 발생했습니다.",
                                              "detail": "GCS upload failed"
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping(value = "/photos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPhotos(
            @Parameter(description = "업로드할 이미지 파일 목록", required = true)
            @RequestParam("files") List<MultipartFile> files,
            @Parameter(
                    description = "각 파일의 메타데이터를 담은 JSON 배열 (String 형태로 전송)\n\n예시:\n[{\"originalName\":\"photo1.jpg\",\"takenAt\":\"2025-11-12T10:00:00\",\"latitude\":35.1584,\"longitude\":129.1603}]",
                    required = true
            )
            @RequestParam("metadata") String metadata
    ) throws IOException {
        // 현재 로그인한 유저 가져오기
        User user = getCurrentUser();

        // 서비스 호출
        List<Photo> photos = photoService.uploadPhotos(files, metadata, user);

        // 성공 응답
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", 201,
                "message", "사진 업로드 성공",
                "uploadedPhotos", photos
        ));
    }

    /**
     * 모든 사진 조회
     */
    @Operation(
            summary = "모든 사진 조회",
            description = "업로드된 모든 사진을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 목록 조회 성공"
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/photos")
    public ResponseEntity<List<Photo>> getAllPhotos() {
        return ResponseEntity.ok(photoService.getAllPhotos());
    }

    /**
     * 특정 사진 조회
     */
    @Operation(
            summary = "특정 사진 조회",
            description = "사진ID로 특정 사진을 조회합니다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사진 조회 성공"),
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "사진을 찾을 수 없습니다. ID: 999"
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/photos/{photoId}")
    public ResponseEntity<Photo> getPhotoById(
            @Parameter(description = "조회할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId
    ) {
        return ResponseEntity.ok(photoService.getPhotoById(photoId));
    }

    /**
     * 지도 마커 조회
     */
    @Operation(
            summary = "지도 마커용 사진 조회",
            description = "위치 정보가 있는 사진들만 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/map-photos")
    public ResponseEntity<List<PhotoDto>> getMapPhotos() {
        User user = getCurrentUser();
        return ResponseEntity.ok(photoService.getMyMapPhotos(user.getId()));
    }

    /**
     * 사진 코멘트 작성
     */
    @Operation(summary = "사진 코멘트 작성", description = "특정 사진에 코멘트을 작성합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "코멘트 작성 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "존재하지 않는 사진입니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping("/photos/{photoId}/comments")
    public ResponseEntity<?> createComment(
            @PathVariable Long photoId,
            @RequestBody TravelDto.CommentRequest request
    ) {
        User user = getCurrentUser();
        Long cmentId = photoService.createComment(photoId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "cmentId", cmentId,
                "message", "코멘트가 작성되었습니다."
        ));
    }

    /**
     * 사진 코멘트 수정
     */
    @Operation(summary = "사진 코멘트 수정", description = "사진의 코멘트를 수정합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "코멘트를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "해당 사진에 댓글이 존재하지 않습니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "본인의 댓글만 수정할 수 있습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/photos/{photoId}/comments")
    public ResponseEntity<Void> updateComment(
            @PathVariable Long photoId,
            @RequestBody TravelDto.CommentRequest request
    ) {
        User user = getCurrentUser();
        photoService.updateCommentByPhotoId(photoId, request, user);
        return ResponseEntity.ok().build();
    }

    /**
     * 사진 코멘트 삭제
     */
    @Operation(summary = "사진 코멘트 삭제", description = "사진의 코멘트를 삭제합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "코멘트를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "해당 사진에 댓글이 존재하지 않습니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "댓글을 삭제할 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping("/photos/{photoId}/comments")
    public ResponseEntity<Void> deleteComment(@PathVariable Long photoId) {
        User user = getCurrentUser();
        photoService.deleteCommentByPhotoId(photoId, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * 사진 삭제
     */
    @Operation(summary = "사진 삭제", description = "사진을 삭제합니다")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "삭제 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "사진과 코멘트가 삭제되었습니다.",
                                              "deletedPhotoId": 123
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "존재하지 않는 사진입니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "사진 삭제 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping("/photos/{photoId}")
    public ResponseEntity<?> deletePhoto(@PathVariable Long photoId) {
        User user = getCurrentUser();
        Long deletedId = photoService.deletePhoto(photoId, user.getId());
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "사진과 코멘트가 삭제되었습니다.",
                "deletedPhotoId", deletedId
        ));
    }

    /**
     * 사진 촬영시간 수정
     */
    @Operation(summary = "사진 촬영시간 수정", description = "사진의 촬영 시간을 수정합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "사진이 존재하지 않습니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "촬영 시간을 수정할 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/photos/{photoId}/taken-at")
    public ResponseEntity<?> updatePhotoTakenAt(
            @PathVariable Long photoId,
            @RequestBody Map<String, String> request
    ) {
        User user = getCurrentUser();
        LocalDateTime newTakenAt = LocalDateTime.parse(request.get("takenAt"));
        photoService.updateTakenAt(photoId, newTakenAt, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "촬영 시간이 수정되었습니다."
        ));
    }

    /**
     * 사진을 특정 날짜로 이동
     */
    @Operation(summary = "사진을 여행 일차로 이동", description = "사진을 다른 여행 일차로 이동시킵니다")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이동 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "사진 또는 일차를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "사진이 존재하지 않습니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "사진을 이동할 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/photos/{photoId}/travel-day")
    public ResponseEntity<?> movePhotoToDay(
            @PathVariable Long photoId,
            @RequestBody MovePhotoRequest request
    ) {
        User user = getCurrentUser();
        photoService.movePhotoToDay(photoId, request.getDayId(), user.getId());
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "사진이 이동되었습니다."
        ));
    }

    /**
     * 사진 정보 수정 (PATCH)
     */
    @Operation(summary = "사진 정보 수정", description = "사진의 정보를 수정합니다 (null이 아닌 필드만)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "BAD_REQUEST",
                                              "message": "사진이 존재하지 않습니다."
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
                                              "message": "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                                              "detail": "Full authentication is required to access this resource"
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
                                              "error": "SECURITY_VIOLATION",
                                              "message": "사진을 수정할 권한이 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PatchMapping("/photos/{photoId}")
    public ResponseEntity<?> updatePhoto(
            @PathVariable Long photoId,
            @RequestBody PhotoUpdateRequest request
    ) {
        User user = getCurrentUser();
        photoService.updatePhoto(photoId, request, user);
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "사진이 수정되었습니다."
        ));
    }

    /**
     * 현재 로그인한 사용자 조회
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));
    }
}
