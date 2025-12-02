package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/photos")
@RequiredArgsConstructor // 생성자 주입 자동 생성
public class PhotoController {

    private final PhotoService photoService; // 서비스 연결

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPhotos(
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart("metadata") String metadata
    ) {
        try {
            // 서비스 호출
            List<Photo> photos = photoService.uploadPhotos(files, metadata);

            // 결과 반환
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "uploadedPhotos", photos
            ));

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("파일 업로드 실패");
        }
    }
}