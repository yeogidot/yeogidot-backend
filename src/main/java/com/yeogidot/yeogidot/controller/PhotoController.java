package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.MovePhotoRequest;
import com.yeogidot.yeogidot.dto.PhotoDto;
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
import java.util.stream.Collectors;

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
                          "id": 1,
                          "filePath": "https://storage.googleapis.com/.../photo1.jpg",
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
                    description = "잘못된 요청 (메타데이터 형식 오류)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "error": "메타데이터 형식이 올바르지 않습니다"
                    }
                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "error": "파일 업로드 실패: 저장 공간 부족"
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
                    description = "사진 메타데이터 (JSON 배열 형식)",
                    required = true,
                    example = """
                [
                  {
                    "latitude": 37.5665,
                    "longitude": 126.9780,
                    "takenAt": "2024-01-15T14:30:00"
                  }
                ]
                """
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
    @GetMapping("/photos/{photoId}")
    public ResponseEntity<?> getPhotoById(@PathVariable Long photoId) {
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
    @GetMapping("/map-photos")
    public ResponseEntity<List<PhotoDto>> getMapPhotos() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));

        return ResponseEntity.ok(photoService.getMyMapPhotos(user.getId()));
    }

    /**
     * 사진 댓글 작성
     */
    @PostMapping("/photos/{photoId}/comments")
    public ResponseEntity<Void> createComment(
            @PathVariable Long photoId,
            @RequestBody TravelDto.CommentRequest request) {
        User user = getCurrentUser();
        photoService.createComment(photoId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 사진 댓글 수정
     */
    @PutMapping("/comments/{cmentId}")
    public ResponseEntity<Void> updateComment(
            @PathVariable Long cmentId,
            @RequestBody TravelDto.CommentRequest request) {
        User user = getCurrentUser();
        photoService.updateComment(cmentId, request, user);
        return ResponseEntity.ok().build();
    }

    /**
     * 사진 삭제 API
     */
    @Operation(
            summary = "사진 삭제",
            description = "특정 사진을 삭제합니다. 해당 사진에 달린 모든 댓글도 함께 삭제됩니다. 본인이 업로드한 사진만 삭제 가능합니다."
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
                      "message": "해당 사진을 찾을 수 없습니다."
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
    @PutMapping("/photos/{photoId}/taken-at")
    public ResponseEntity<?> updatePhotoTakenAt(
            @PathVariable Long photoId,
            @RequestBody Map<String, String> request) {
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
    @PutMapping("/photos/{photoId}/travel-day")
    public ResponseEntity<?> movePhotoToDay(
            @PathVariable Long photoId,
            @RequestBody MovePhotoRequest request) {
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
     * 현재 로그인한 사용자 조회
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));
    }
}