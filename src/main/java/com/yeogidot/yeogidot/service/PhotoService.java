package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.PhotoDetailResponse;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .fileUrl(photo.getFilePath())    // 파일 경로(URL)
                .takenAt(photo.getTakenAt())     // 촬영 시간
                .latitude(photo.getLatitude())   // 위도
                .longitude(photo.getLongitude()) // 경도
                .build();
    }
}