package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final UserRepository userRepository;

    /**
     * 사진 업로드
     */
    @PostMapping(value = "/photos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPhotos(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("metadata") String metadata
    ) {
        try {
            System.out.println("📸 받은 파일 개수: " + files.size());
            System.out.println("📋 메타데이터: " + metadata);

            // 서비스 호출
            List<Photo> photos = photoService.uploadPhotos(files, metadata);

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
     * 🧪 테스트용: 모든 사진의 지도 마커 조회 (인증 불필요)
     */
    @GetMapping("/photos/map-markers")
    public ResponseEntity<?> getAllMapMarkers() {
        try {
            // 위치 정보가 있는 모든 사진 조회
            List<Photo> photos = photoService.getAllPhotos();
            
            List<PhotoDto> markers = photos.stream()
                    .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                    .map(photo -> PhotoDto.builder()
                            .photoId(photo.getId())
                            .latitude(photo.getLatitude())
                            .longitude(photo.getLongitude())
                            .thumbnailUrl(photo.getFilePath())
                            .build())
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(markers);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "지도 마커 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * 사진 댓글 작성
     */
    @PostMapping("/v1/photos/{photoId}/comments")
    public ResponseEntity<Void> createComment(
            @PathVariable Long photoId,
            @RequestBody TravelDto.CommentRequest request) {
        photoService.createComment(photoId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 사진 댓글 수정
     */
    @PutMapping("/v1/comments/{cmentId}")
    public ResponseEntity<Void> updateComment(
            @PathVariable Long cmentId,
            @RequestBody TravelDto.CommentRequest request) {
        photoService.updateComment(cmentId, request);
        return ResponseEntity.ok().build();
    }
}
