package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.PhotoDto; // (DTO 패키지에 PhotoDto 있어야 함)
import com.yeogidot.yeogidot.entity.Photo;
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
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final GcsService gcsService;
    private final ObjectMapper objectMapper;

    //  내부 DTO
    @Data
    public static class PhotoMetaDto {
        private String originalName;
        private LocalDateTime takenAt;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }

    @Transactional
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson) throws IOException {
        List<PhotoMetaDto> metaList = objectMapper.readValue(metadataJson, new TypeReference<>() {});
        List<Photo> savedPhotos = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            String gcsUrl = gcsService.uploadFile(files.get(i));
            PhotoMetaDto meta = metaList.get(i);

            Photo photo = Photo.builder()
                    .url(gcsUrl)
                    .originalName(meta.getOriginalName())
                    .takenAt(meta.getTakenAt())
                    .latitude(meta.getLatitude())
                    .longitude(meta.getLongitude())
                    .dayId(null)
                    .build();
            savedPhotos.add(photoRepository.save(photo));
        }
        return savedPhotos;
    }

    // [기능] 지도 마커 조회
    @Transactional(readOnly = true)
    public List<PhotoDto> getMyMapPhotos(Long userId) {
        return photoRepository.findAllByUserId(userId).stream()
                .map(photo -> PhotoDto.builder()
                        .photoId(photo.getPhotoId())
                        .latitude(photo.getLatitude())
                        .longitude(photo.getLongitude())
                        .thumbnailUrl(photo.getUrl())
                        .build())
                .collect(Collectors.toList());
    }
}