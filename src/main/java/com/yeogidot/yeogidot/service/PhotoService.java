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
import java.time.ZonedDateTime;
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
    private final GeoCodingService geoCodingService;
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

            // 3. 지역 정보 조회 (위도/경도가 있는 경우)
            String region = null;
            if (lat != null && lng != null) {
                region = geoCodingService.getDistrictFromCoordinates(lat, lng);
            }

            // 4. 촬영 시간 파싱 (타임존 정보 처리)
            LocalDateTime takenAt = parseTakenAt(meta.getTakenAt());

            // 5. Photo 엔티티 생성 (user 설정, travelDay는 null로 시작)
            Photo photo = Photo.builder()
                    .user(user)  // 사진 업로드한 사용자 설정
                    .filePath(gcsUrl)
                    .originalName(meta.getOriginalName())
                    .takenAt(takenAt)
                    .latitude(lat)
                    .longitude(lng)
                    .region(region)  // 지역 정보 저장
                    .build();

            // 6. DB에 저장
            savedPhotos.add(photoRepository.save(photo));
        }

        return savedPhotos;
    }
    
    /**
     * 타임존 정보가 포함된 날짜 문자열을 LocalDateTime으로 변환
     */
    private LocalDateTime parseTakenAt(String takenAtStr) {
        try {
            // 타임존 정보가 있는 경우 (예: 2024-08-02T22:38:06+09:00)
            if (takenAtStr.contains("+") || takenAtStr.endsWith("Z")) {
                return ZonedDateTime.parse(takenAtStr).toLocalDateTime();
            }
            // 타임존 정보가 없는 경우 (예: 2025-11-12T10:00:00)
            return LocalDateTime.parse(takenAtStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("잘못된 날짜 형식입니다: " + takenAtStr, e);
        }
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

    // 댓글 작성 - 누구나 가능
    public Long createComment(Long photoId, TravelDto.CommentRequest request, User user) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        // 권한 검증 제거 (누구나 댓글 작성 가능)
        Cment cment = Cment.builder()
                .photo(photo)
                .writer(user)  // ⭐ 작성자 저장
                .content(request.getContent())
                .build();

        return cmentRepository.save(cment).getId();
    }

    // 사진 ID로 댓글 수정
    public void updateCommentByPhotoId(Long photoId, TravelDto.CommentRequest request, User user) {
        // photoId로 댓글 찾기
        Cment cment = cmentRepository.findByPhotoId(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진에 댓글이 존재하지 않습니다."));

        // 작성자 본인 확인
        if (!cment.getWriter().getId().equals(user.getId())) {
            throw new SecurityException("본인의 댓글만 수정할 수 있습니다.");
        }

        // 내용 수정
        cment.updateContent(request.getContent());
    }

    // 사진 ID로 댓글 삭제
    public void deleteCommentByPhotoId(Long photoId, User user) {
        // photoId로 댓글 찾기
        Cment cment = cmentRepository.findByPhotoId(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진에 댓글이 존재하지 않습니다."));

        // 권한 확인
        boolean isWriter = cment.getWriter().getId().equals(user.getId());
        boolean isPhotoOwner = cment.getPhoto().getUser().getId().equals(user.getId());

        if (!isWriter && !isPhotoOwner) {
            throw new SecurityException("댓글을 삭제할 권한이 없습니다.");
        }

        // 삭제
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
        photoRepository.flush(); // 즉시 DELETE 실행

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
    
    /**
     * 사진 정보 통합 수정 (PATCH)
     * - null이 아닌 필드만 수정됨
     */
    @Transactional
    public void updatePhoto(Long photoId, com.yeogidot.yeogidot.dto.PhotoUpdateRequest request, User user) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("사진이 존재하지 않습니다."));
        
        // 권한 검증
        if (!photo.getUser().getId().equals(user.getId())) {
            throw new SecurityException("사진을 수정할 권한이 없습니다.");
        }
        
        // 촬영 시간 수정
        if (request.getTakenAt() != null) {
            photo.updateTakenAt(request.getTakenAt());
        }
        
        // 여행 일차 이동
        if (request.getDayId() != null) {
            TravelDay targetDay = travelDayRepository.findById(request.getDayId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 날짜입니다."));
            
            // 목적지 여행의 소유자 확인
            if (!targetDay.getTravel().getUser().getId().equals(user.getId())) {
                throw new SecurityException("해당 여행에 사진을 추가할 권한이 없습니다.");
            }
            
            photo.setTravelDay(targetDay);
        }
        
        // 위치 정보 수정
        if (request.getLatitude() != null && request.getLongitude() != null) {
            BigDecimal lat = BigDecimal.valueOf(request.getLatitude());
            BigDecimal lng = BigDecimal.valueOf(request.getLongitude());
            photo.updateLocation(lat, lng);
            
            // 위치 변경 시 지역 정보도 업데이트
            String newRegion = geoCodingService.getDistrictFromCoordinates(lat, lng);
            photo.updateRegion(newRegion);
        }
    }
}
