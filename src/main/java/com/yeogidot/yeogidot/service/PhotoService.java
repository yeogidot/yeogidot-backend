package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.CmentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
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
    private final GcsService gcsService;
    private final ObjectMapper objectMapper;
    private final TravelDayRepository travelDayRepository;

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
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson, User user) throws IOException {
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

            // 3. Photo 엔티티 생성 (user 설정, travelDay는 null로 시작)
            Photo photo = Photo.builder()
                    .user(user)  // 사진 업로드한 사용자 설정
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
    public Long createComment(Long photoId, TravelDto.CommentRequest request, User user) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        // 권한 검증: 본인 사진에만 댓글 작성 가능
        if (!photo.getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 사진에 댓글을 작성할 권한이 없습니다.");
        }

        Cment cment = Cment.builder()
                .photo(photo)
                .content(request.getContent())
                .build();

        return cmentRepository.save(cment).getId();
    }

    /**
     * 댓글 수정 기능
     */
    public void updateComment(Long cmentId, TravelDto.CommentRequest request, User user) {
        Cment cment = cmentRepository.findById(cmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코멘트입니다."));
        
        // 권한 검증: 댓글이 달린 사진의 소유자만 수정 가능
        if (!cment.getPhoto().getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 댓글을 수정할 권한이 없습니다.");
        }
        
        cment.updateContent(request.getContent());
    }
    /**
     * 댓글 삭제 기능
     */
    @Transactional
    public void deleteComment(Long cmentId, User user) {
        Cment cment = cmentRepository.findById(cmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 댓글입니다."));

        // 권한 검증: 댓글이 달린 사진의 소유자만 삭제 가능
        if (!cment.getPhoto().getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 댓글을 삭제할 권한이 없습니다.");
        }

        cmentRepository.delete(cment);
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

    /// 사진 삭제 기능
    @Transactional
    public Long deletePhoto(Long photoId, Long currentUserId) {

        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        // 권한 검증
        validatePhotoOwnership(photo, currentUserId);

        // 대표 사진 처리
        if (photo.getTravelDay() != null) {
            Travel travel = photo.getTravelDay().getTravel();
            if (travel.getRepresentativePhotoId() != null &&
                    travel.getRepresentativePhotoId().equals(photoId)) {
                travel.updateRepresentativePhoto(null);
            }
        }

        // GCS 파일 삭제
        gcsService.deleteFile(photo.getFilePath());

        // DB 삭제
        photoRepository.delete(photo);

        return photoId;
    }

    // 권한 검증 헬퍼 메소드
    private void validatePhotoOwnership(Photo photo, Long currentUserId) {
        // Photo 엔티티의 user로 직접 권한 확인 (TravelDay 여부와 무관)
        if (!photo.getUser().getId().equals(currentUserId)) {
            throw new SecurityException("사진 삭제 권한이 없습니다."); // 403 유발
        }
    }

    /**
     * 촬영 시간 수정 기능
     */
    @Transactional
    public void updateTakenAt(Long photoId, LocalDateTime newTakenAt, User user) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("사진이 존재하지 않습니다."));
        
        // 권한 검증
        if (!photo.getUser().getId().equals(user.getId())) {
            throw new SecurityException("촬영 시간을 수정할 권한이 없습니다.");
        }
        
        photo.updateTakenAt(newTakenAt);
    }

    /**
     * 사진을 특정 날짜로 이동
     */
    @Transactional
    public void movePhotoToDay(Long photoId, Long dayId, Long currentUserId) {
        // 사진 조회
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("사진이 존재하지 않습니다."));
        
        // 목적지 TravelDay 조회
        TravelDay targetDay = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 날짜입니다."));
        
        // 권한 검증: 사진의 소유자와 목적지 여행의 소유자가 같은지 확인
        if (photo.getTravelDay() != null) {
            Long photoOwnerId = photo.getTravelDay().getTravel().getUser().getId();
            if (!photoOwnerId.equals(currentUserId)) {
                throw new SecurityException("사진을 이동할 권한이 없습니다.");
            }
        }
        
        Long targetOwnerId = targetDay.getTravel().getUser().getId();
        if (!targetOwnerId.equals(currentUserId)) {
            throw new SecurityException("해당 여행에 사진을 추가할 권한이 없습니다.");
        }
        
        // 사진 이동
        photo.setTravelDay(targetDay);
    }
}
