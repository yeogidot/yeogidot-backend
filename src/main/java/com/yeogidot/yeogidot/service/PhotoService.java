package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Cment;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.CmentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

//@Service // GCS 키 등록시 주석 해제
@RequiredArgsConstructor
@Transactional
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final CmentRepository cmentRepository;
    private final GcsService gcsService;
    private final ObjectMapper objectMapper;

    /**
     * 프론트엔드에서 받는 메타데이터 DTO
     */
    @Data
    public static class PhotoMetaDto {
        private String originalName;
        private String takenAt;  // ISO 8601 문자열
        private Double latitude;
        private Double longitude;
    }

    /**
     * 여러 사진 업로드 (여행에 연결하지 않고 독립적으로 저장)
     */
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson) throws IOException {
        List<PhotoMetaDto> metaList = objectMapper.readValue(metadataJson, new TypeReference<>() {});

        if (files.size() != metaList.size()) {
            throw new IllegalArgumentException("파일 개수와 메타데이터 개수가 일치하지 않습니다.");
        }

        List<Photo> savedPhotos = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            PhotoMetaDto meta = metaList.get(i);

            // 1. GCS에 파일 업로드
            String gcsUrl = gcsService.uploadFile(file);

            // 2. latitude/longitude가 없으면 null 처리
            BigDecimal lat = meta.getLatitude() != null 
                ? BigDecimal.valueOf(meta.getLatitude()) 
                : null;
            
            BigDecimal lng = meta.getLongitude() != null 
                ? BigDecimal.valueOf(meta.getLongitude()) 
                : null;

            // 3. Photo 엔티티 생성 (travelDay는 null로 시작)
            Photo photo = Photo.builder()
                    .filePath(gcsUrl)
                    .originalName(meta.getOriginalName())
                    .takenAt(LocalDateTime.parse(meta.getTakenAt()))
                    .latitude(lat)
                    .longitude(lng)
                    .build();

            // 4. DB에 저장
            savedPhotos.add(photoRepository.save(photo));
        }

        return savedPhotos;
    }

    /**
     * 지도 마커 조회 기능
     */
    @Transactional(readOnly = true)
    public List<PhotoDto> getMyMapPhotos(Long userId) {
        return photoRepository.findAllByUserId(userId).stream()
                .map(photo -> PhotoDto.builder()
                        .photoId(photo.getId())
                        .latitude(photo.getLatitude())
                        .longitude(photo.getLongitude())
                        .thumbnailUrl(photo.getFilePath())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 댓글 작성 기능
     */
    public Long createComment(Long photoId, TravelDto.CommentRequest request) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        Cment cment = Cment.builder()
                .photo(photo)
                .content(request.getContent())
                .build();

        return cmentRepository.save(cment).getId();
    }

    /**
     * 댓글 수정 기능
     */
    public void updateComment(Long cmentId, TravelDto.CommentRequest request) {
        Cment cment = cmentRepository.findById(cmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코멘트입니다."));
        cment.updateContent(request.getContent());
    }

    /**
     * 사진 상세 정보 조회
     */
    @Transactional(readOnly = true)
    public Photo getPhotoDetail(Long photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진이 존재하지 않습니다. id=" + photoId));
    }

    /**
     * 모든 사진 조회
     */
    @Transactional(readOnly = true)
    public List<Photo> getAllPhotos() {
        return photoRepository.findAll();
    }

    /**
     * 특정 사진 조회
     */
    @Transactional(readOnly = true)
    public Photo getPhotoById(Long id) {
        return photoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("사진을 찾을 수 없습니다. ID: " + id));
    }
}
