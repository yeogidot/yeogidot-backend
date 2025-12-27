package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.PhotoDto;
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
@RequestMapping("/api") // 공통 URL
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final UserRepository userRepository;

    // [기능] POST /api/photos/upload
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

    // [기능] GET /api/map-photos
    @GetMapping("/map-photos")
    public ResponseEntity<List<PhotoDto>> getMapPhotos() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));

        return ResponseEntity.ok(photoService.getMyMapPhotos(user.getUserId()));
    }
}