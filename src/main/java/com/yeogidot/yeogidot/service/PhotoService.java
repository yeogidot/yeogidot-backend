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

@Service
@RequiredArgsConstructor
@Transactional
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final CmentRepository cmentRepository;
//    private final GcsService gcsService;
    private final ObjectMapper objectMapper;

    // 사진 메타데이터 내부 DTO
    @Data
    public static class PhotoMetaDto {
        private String originalName;
        private LocalDateTime takenAt;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }

    // 사진 업로드 기능
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson) throws IOException {
        throw new UnsupportedOperationException("GCS 키 설정이 필요합니다."); // 임시로 사용

        /* 나중에 GCS 키 받으면 사용할 메서드
        List<PhotoMetaDto> metaList = objectMapper.readValue(metadataJson, new TypeReference<>() {});
        List<Photo> savedPhotos = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            String gcsUrl = gcsService.uploadFile(files.get(i));
            PhotoMetaDto meta = metaList.get(i);

            Photo photo = Photo.builder()
                    .filePath(gcsUrl) // 엔티티 필드명 filePath로 통일
                    .originalName(meta.getOriginalName())
                    .takenAt(meta.getTakenAt())
                    .latitude(meta.getLatitude())
                    .longitude(meta.getLongitude())
                    .build();
            savedPhotos.add(photoRepository.save(photo));
        }
        return savedPhotos; */
    }

    // 지도 마커 조회 기능
    @Transactional(readOnly = true)
    public List<PhotoDto> getMyMapPhotos(Long userId) {
        return photoRepository.findAllByUserId(userId).stream()
                .map(photo -> PhotoDto.builder()
                        .photoId(photo.getId()) // photoId -> id 로 수정 (엔티티 일치)
                        .latitude(photo.getLatitude())
                        .longitude(photo.getLongitude())
                        .thumbnailUrl(photo.getFilePath()) // url -> filePath 로 수정
                        .build())
                .collect(Collectors.toList());
    }

    // 댓글 작성 기능
    public Long createComment(Long photoId, TravelDto.CommentRequest request) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        Cment cment = Cment.builder()
                .photo(photo)
                .content(request.getContent())
                .build();

        return cmentRepository.save(cment).getId();
    }

    // 댓글 수정 기능
    public void updateComment(Long cmentId, TravelDto.CommentRequest request) {
        Cment cment = cmentRepository.findById(cmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코멘트입니다."));
        cment.updateContent(request.getContent());
    }

    // 사진 상세 정보 조회
    @Transactional(readOnly = true)
    public Photo getPhotoDetail(Long photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진이 존재하지 않습니다. id=" + photoId));
    }
}