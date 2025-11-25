package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal; // <-- 추가됨
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final GcsService gcsService;
    private final ObjectMapper objectMapper;

    @Data
    public static class PhotoMetaDto {
        private String originalName;
        private LocalDateTime takenAt;
        private BigDecimal latitude;  // <-- 여기 변경!
        private BigDecimal longitude; // <-- 여기 변경!
    }

    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson) throws IOException {
        List<PhotoMetaDto> metaList = objectMapper.readValue(metadataJson, new TypeReference<>() {});

        if (files.size() != metaList.size()) {
            throw new IllegalArgumentException("파일 개수와 메타데이터 개수가 다릅니다.");
        }

        List<Photo> savedPhotos = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            PhotoMetaDto meta = metaList.get(i);

            String gcsUrl = gcsService.uploadFile(file);

            Photo photo = Photo.builder()
                    .url(gcsUrl)
                    .originalName(meta.getOriginalName())
                    .takenAt(meta.getTakenAt())
                    .latitude(meta.getLatitude())   // 이제 타입이 맞아서 에러 안 남
                    .longitude(meta.getLongitude()) // 이제 타입이 맞아서 에러 안 남
                    .dayId(null)
                    .build();

            savedPhotos.add(photoRepository.save(photo));
        }
        return savedPhotos;
    }
}