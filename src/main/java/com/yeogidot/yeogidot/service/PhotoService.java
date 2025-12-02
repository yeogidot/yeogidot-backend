package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.PhotoDetailResponse;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * [사진 서비스]
 * - 사진 조회 등의 로직을 처리합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoService {

    private final PhotoRepository photoRepository;

    /**
     * 사진 상세 조회
     */
    public PhotoDetailResponse getPhotoDetail(Long photoId) {
        // 1. DB 조회 (없으면 예외)
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진이 존재하지 않습니다. id=" + photoId));

        // 2. Entity -> DTO 변환
        return PhotoDetailResponse.builder()
                .photoId(photo.getId())
                .fileUrl(photo.getFilePath())
                .takenAt(photo.getTakenAt())
                .latitude(photo.getLatitude())
                .longitude(photo.getLongitude())
                .build();
    }

    /**
     * 사진 업로드
     */
    @Transactional
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadata) throws IOException {
        List<Photo> uploadedPhotos = new ArrayList<>();

        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            String filePath = saveFile(file);

            Photo photo = Photo.builder()
                    .filePath(filePath)
                    .build();

            uploadedPhotos.add(photoRepository.save(photo));
        }

        return uploadedPhotos;
    }

    private String saveFile(MultipartFile file) throws IOException {
        String uploadDir = "uploads/photos/";
        Path uploadPath = Paths.get(uploadDir);

        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        return filePath.toString();
    }
}
