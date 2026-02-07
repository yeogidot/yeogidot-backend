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
                                              "uploadedPhotos": [
                                                {
                                                  "createdDate": "2026-01-25T21:31:03.277223",
                                                  "modifiedDate": "2026-01-25T22:42:46.115681",
                                                  "id": 1,
                                                  "filePath": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg",
                                                  "originalName": "test.jpg",
                                                  "latitude": 37.5665,
                                                  "longitude": 126.9780,
                                                  "takenAt": "2024-01-15T14:30:00",
                                                  "url": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (메타데이터 형식 오류 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "error": "메타데이터 형식이 올바르지 않습니다."
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
                    responseCode = "500",
                    description = "서버 오류 (파일 업로드 실패 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "error": "파일 업로드 실패: I/O 오류"
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
    ) {
        try {
            System.out.println("받은 파일 개수: " + files.size());
            System.out.println("메타데이터: " + metadata);

            // 현재 로그인한 유저 가져오기
            User user = getCurrentUser();

            // 서비스 호출
            List<Photo> photos = photoService.uploadPhotos(files, metadata, user);

            // 성공 응답
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "uploadedPhotos", photos
            ));

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "파일 업로드 실패: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "예상치 못한 오류: " + e.getMessage()));
        }
    }

    /**
     * 모든 사진 조회
     */
    @Operation(
            summary = "모든 사진 조회",
            description = "업로드된 모든 사진을 조회합니다. JWT 토큰으로 인증된 사용자의 사진만 조회됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            [
                                              {
                                                "createdDate": "2026-01-25T21:31:03.277223",
                                                "modifiedDate": "2026-01-25T22:42:46.115681",
                                                "id": 14,
                                                "filePath": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg",
                                                "originalName": "test.jpg",
                                                "latitude": 35.1584,
                                                "longitude": 129.1603,
                                                "takenAt": "2026-01-15T14:30:00",
                                                "url": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg"
                                              },
                                              {
                                                "createdDate": "2026-01-25T21:36:59.021477",
                                                "modifiedDate": "2026-01-25T22:42:46.123844",
                                                "id": 15,
                                                "filePath": "https://storage.googleapis.com/yeogidot-storage/photo2.jpg",
                                                "originalName": "test.jpg",
                                                "latitude": 35.1584,
                                                "longitude": 129.1603,
                                                "takenAt": "2026-01-12T10:00:00",
                                                "url": "https://storage.googleapis.com/yeogidot-storage/photo2.jpg"
                                              }
                                            ]
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
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "error": "사진 조회 실패: 서버 오류"
                                            }
                                            """
                            )
                    )
            ),

    })
    @GetMapping("/photos")
    public ResponseEntity<?> getAllPhotos() {
        try {
            List<Photo> photos = photoService.getAllPhotos();
            return ResponseEntity.ok(photos);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "사진 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * 특정 사진 조회
     */
    @Operation(
            summary = "특정 사진 조회",
            description = "사진ID로 특정 사진을 조회합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "createdDate": "2026-01-25T21:31:03.277223",
                                              "modifiedDate": "2026-01-25T22:42:46.115681",
                                              "id": 1,
                                              "filePath": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg",
                                              "originalName": "test.jpg",
                                              "latitude": 37.5665,
                                              "longitude": 126.9780,
                                              "takenAt": "2024-01-15T14:30:00",
                                              "url": "https://storage.googleapis.com/yeogidot-storage/photo1.jpg"
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
                    responseCode = "404",
                    description = "사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "error": "사진을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "error": "사진 조회 실패: 서버 오류"
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/photos/{photoId}")
    public ResponseEntity<?> getPhotoById(
            @Parameter(description = "조회할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId
    ) {
        try {
            Photo photo = photoService.getPhotoById(photoId);
            return ResponseEntity.ok(photo);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "사진 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * 지도 마커 조회 (위치 정보가 있는 사진만) - 인증 필요
     */
    @Operation(
            summary = "지도 마커용 사진 조회",
            description = "위치 정보가 있는 사진들만 조회합니다. 지도에 마커로 표시하기 위한 API입니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "지도 마커 사진 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            [
                                              {
                                                "photoId": 1,
                                                "thumbnailUrl": "https://storage.googleapis.com/bucket/photo1.jpg",
                                                "latitude": 37.5665,
                                                "longitude": 126.9780
                                              },
                                              {
                                                "photoId": 2,
                                                "thumbnailUrl": "https://storage.googleapis.com/bucket/photo2.jpg",
                                                "latitude": 35.1796,
                                                "longitude": 129.0756
                                              }
                                            ]
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
            )
    })
    @GetMapping("/map-photos")
    public ResponseEntity<List<PhotoDto>> getMapPhotos() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));

        return ResponseEntity.ok(photoService.getMyMapPhotos(user.getId()));
    }

    /**
     * 사진 코멘트 작성
     */
    @Operation(
            summary = "사진 코멘트 작성",
            description = "특정 사진에 코멘트을 작성합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "코멘트 작성 성공"
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
                    responseCode = "404",
                    description = "사진을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "사진을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping("/photos/{photoId}/comments")
    public ResponseEntity<?> createComment(
            @Parameter(description = "코멘트를 달 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "코멘트 내용",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "content": "멋진 사진이네요!"
                                            }
                                            """
                            )
                    )
            )
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
    /**
     * 사진 코멘트 수정
     */
    @Operation(
            summary = "사진 코멘트 수정",
            description = "사진 ID를 사용하여 해당 사진에 달린 코멘트를 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "코멘트 수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "코멘트를 찾을 수 없음")
    })
    @PutMapping("/photos/{photoId}/comments") // URL 변경: photos/{id}/comments
    public ResponseEntity<Void> updateComment(
            @Parameter(description = "코멘트가 달린 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId, // cmentId 대신 photoId를 받음
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 코멘트 내용",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                            {
                                              "content": "수정된 코멘트 내용입니다."
                                            }
                                            """)
                    )
            )
            @RequestBody TravelDto.CommentRequest request
    ) {
        User user = getCurrentUser();
        // 서비스 메서드 이름도 명확하게 변경 (기존 updateComment -> updateCommentByPhotoId)
        photoService.updateCommentByPhotoId(photoId, request, user);
        return ResponseEntity.ok().build();
    }

    /**
     * 사진 코멘트 삭제
     */
    @Operation(summary = "사진 코멘트 삭제", description = "사진 ID를 통해 해당 사진의 코멘트를 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "코멘트 삭제 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (다른 사용자의 코멘트)"),
            @ApiResponse(responseCode = "404", description = "코멘트를 찾을 수 없음")
    })
    @DeleteMapping("/photos/{photoId}/comments") // 주소 변경
    public ResponseEntity<Void> deleteComment(
            @Parameter(description = "코멘트를 삭제할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId // 파라미터 변경
    ) {
        User user = getCurrentUser();
        photoService.deleteCommentByPhotoId(photoId, user); // 서비스 호출 변경!
        return ResponseEntity.noContent().build();
    }

    /**
     * 사진 삭제 API
     */
    @Operation(
            summary = "사진 삭제",
            description = "특정 사진을 삭제합니다. 해당 사진에 달린 모든 코멘트도 함께 삭제됩니다. 본인이 업로드한 사진만 삭제 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 삭제 성공",
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
                    description = "권한 없음 (다른 사용자의 사진)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN_ACCESS",
                                              "message": "본인의 사진만 삭제할 수 있습니다."
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
                                              "error": "PHOTO_NOT_FOUND",
                                              "message": "사진을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping("/photos/{photoId}")
    public ResponseEntity<?> deletePhoto(
            @Parameter(description = "삭제할 사진의 ID", required = true, example = "123")
            @PathVariable Long photoId
    ) {
        try {
            User user = getCurrentUser();
            Long deletedId = photoService.deletePhoto(photoId, user.getId());

            // 성공 (200 OK)
            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "사진과 코멘트가 삭제되었습니다.",
                    "deletedPhotoId", deletedId
            ));

        } catch (IllegalArgumentException e) {
            // 실패 (404 Not Found - 사진 없음)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", 404,
                    "error", "PHOTO_NOT_FOUND",
                    "message", e.getMessage()
            ));

        } catch (SecurityException e) {
            // 실패 (403 Forbidden - 권한 없음)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", 403,
                    "error", "FORBIDDEN_ACCESS",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 사진 촬영시간 수정 API
     */
    @Operation(
            summary = "사진 촬영시간 수정",
            description = "특정 사진의 촬영 시간을 수정합니다. 본인이 업로드한 사진만 수정 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "촬영 시간 수정 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "촬영 시간이 수정되었습니다."
                                            }
                                            """
                            )
                    )
            ),

            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (날짜 형식 오류 등)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "INVALID_REQUEST",
                                              "message": "잘못된 요청입니다: 날짜 형식이 올바르지 않습니다."
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
                    description = "권한 없음 (다른 사용자의 사진)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "본인의 사진만 수정할 수 있습니다."
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
                                              "error": "PHOTO_NOT_FOUND",
                                              "message": "사진을 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/photos/{photoId}/taken-at")
    public ResponseEntity<?> updatePhotoTakenAt(
            @Parameter(description = "수정할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "새로운 촬영 시간",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "takenAt": "2025-01-15T14:30:00"
                                            }
                                            """
                            )
                    )
            )
            @RequestBody Map<String, String> request
    ) {
        try {
            User user = getCurrentUser();
            // takenAt 문자열을 LocalDateTime으로 변환
            LocalDateTime newTakenAt = LocalDateTime.parse(request.get("takenAt"));

            photoService.updateTakenAt(photoId, newTakenAt, user);

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "촬영 시간이 수정되었습니다."
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", 404,
                    "error", "PHOTO_NOT_FOUND",
                    "message", e.getMessage()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", 403,
                    "error", "FORBIDDEN",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", 400,
                    "error", "INVALID_REQUEST",
                    "message", "잘못된 요청입니다: " + e.getMessage()
            ));
        }
    }

    /**
     * 사진을 특정 날짜로 이동
     */
    @Operation(
            summary = "사진을 특정 여행 일차로 이동",
            description = "사진을 다른 여행 일차(Day)로 이동시킵니다. 본인이 업로드한 사진만 이동 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 이동 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "사진이 이동되었습니다."
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
                    description = "권한 없음 (다른 사용자의 사진)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 403,
                                              "error": "FORBIDDEN",
                                              "message": "본인의 사진만 이동할 수 있습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사진 또는 일차를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 404,
                                              "error": "NOT_FOUND",
                                              "message": "사진 또는 여행 일차를 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PutMapping("/photos/{photoId}/travel-day")
    public ResponseEntity<?> movePhotoToDay(
            @Parameter(description = "이동할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "이동할 목적지 여행 일차 ID",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "dayId": 5
                                            }
                                            """
                            )
                    )
            )
            @RequestBody MovePhotoRequest request
    ) {
        try {
            User user = getCurrentUser();
            photoService.movePhotoToDay(photoId, request.getDayId(), user.getId());

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "사진이 이동되었습니다."
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

    /**
     * 사진 정보 수정 (PATCH) - 통합 API
     */
    @Operation(
            summary = "사진 정보 수정 (통합)",
            description = "사진의 촬영 시간, 위치 정보, 여행 일차를 한 번에 수정합니다. null이 아닌 필드만 수정됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사진 수정 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": 200,
                                              "message": "사진이 수정되었습니다."
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
                                              "message": "사진을 수정할 권한이 없습니다."
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
                                              "message": "사진이 존재하지 않습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PatchMapping("/photos/{photoId}")
    public ResponseEntity<?> updatePhoto(
            @Parameter(description = "수정할 사진의 ID", required = true, example = "1")
            @PathVariable Long photoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 필드 (null이 아닌 필드만 수정됨)",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "촬영 시간만 수정",
                                            value = """
                                                    {
                                                      "takenAt": "2025-01-15T14:30:00"
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "여행 일차만 수정",
                                            value = """
                                                    {
                                                      "dayId": 5
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "여러 필드 동시 수정",
                                            value = """
                                                    {
                                                      "takenAt": "2025-01-15T14:30:00",
                                                      "dayId": 5,
                                                      "latitude": 37.5665,
                                                      "longitude": 126.9780
                                                    }
                                                    """
                                    )
                            }
                    )
            )
            @RequestBody PhotoUpdateRequest request
    ) {
        try {
            User user = getCurrentUser();
            photoService.updatePhoto(photoId, request, user);

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "사진이 수정되었습니다."
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
