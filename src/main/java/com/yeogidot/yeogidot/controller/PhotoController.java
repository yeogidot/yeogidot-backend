package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.TravelDto; // 오른쪽에서 사용
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final UserRepository userRepository;

    // === 사진 업로드 ===
    @PostMapping(value = "/photos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPhotos(
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart("metadata") String metadata
    ) {
        try {
            List<Photo> photos = photoService.uploadPhotos(files, metadata);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("uploadedPhotos", photos));
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("실패");
        }
    }

    // === 지도 사진 조회 ===
    @GetMapping("/map-photos")
    public ResponseEntity<List<PhotoDto>> getMapPhotos() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));

        return ResponseEntity.ok(photoService.getMyMapPhotos(user.getId()));
    }

    // === 사진 댓글 작성 ===
    @PostMapping("/v1/photos/{photoId}/comments")
    public ResponseEntity<Void> createComment(
            @PathVariable Long photoId,
            @RequestBody TravelDto.CommentRequest request) {
        photoService.createComment(photoId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // === 사진 댓글 수정 ===
    @PutMapping("/v1/comments/{cmentId}")
    public ResponseEntity<Void> updateComment(
            @PathVariable Long cmentId,
            @RequestBody TravelDto.CommentRequest request) {
        photoService.updateComment(cmentId, request);
        return ResponseEntity.ok().build();
    }
}