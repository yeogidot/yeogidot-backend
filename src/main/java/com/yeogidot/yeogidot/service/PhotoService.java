package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.PhotoDetailResponse;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import org.springframework.stereotype.Service;

@Service
public class PhotoService {

    private final PhotoRepository photoRepository;

    public PhotoService(PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

    public PhotoDetailResponse getPhotoDetail(Long photoId) {
        // DB에서 ID로 조회
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진이 존재하지 않습니다. id=" + photoId));

        // Entity -> DTO 변환
        return new PhotoDetailResponse(
                photo.getPhotoId(),
                photo.getTitle(),
                photo.getDescription(),
                photo.getImageUrl(),
                photo.getUploadedDate()
        );
    }
}