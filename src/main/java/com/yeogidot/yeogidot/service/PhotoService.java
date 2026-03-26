package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.PhotoDto;
import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoService {

    // 동시 업로드 수 제한 (최대 3장): 폰 사진 다수 동시 처리 시 OOM 방지
    private static final Semaphore uploadSemaphore = new Semaphore(3);

    private final PhotoRepository photoRepository;
    private final CommentRepository commentRepository;
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

    // 허용된 이미지 MIME 타입
    private static final List<String> ALLOWED_MIME_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    // 허용된 파일 확장자
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp"
    );

    /**
     * 여러 사진 업로드 (여행에 연결하지 않고 독립적으로 저장)
     */
    @Transactional
    public List<Photo> uploadPhotos(List<MultipartFile> files, String metadataJson, User user) throws IOException {
        // 파일 타입 검증 (MIME 타입 + 확장자 + 실제 이미지 내용 검사)
        for (MultipartFile file : files) {
            // 1. MIME 타입 검사
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType)) {
                throw new IllegalArgumentException(
                        "허용되지 않는 파일 형식입니다: " + contentType + ". JPEG, PNG, WebP 파일만 업로드 가능합니다."
                );
            }

            // 2. 확장자 검사 (MIME 타입 조작 방어)
            String originalFilename = file.getOriginalFilename();
            if (originalFilename != null) {
                String ext = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase();
                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                    throw new IllegalArgumentException(
                            "허용되지 않는 파일 확장자입니다: " + ext + ". jpg, jpeg, png, webp만 허용됩니다."
                    );
                }
            }

            // 3. 실제 이미지 파일 내용 검사 (확장자·MIME 타입 모두 속여도 차단)
            // WebP는 Java 기본 ImageIO 미지원 → null 반환으로 false positive 발생
            // webp-imageio 등 외부 라이브러리는 Java 21 / Spring Boot 3.x 환경에서
            // JNI 로딩 실패 및 리눅스 배포 환경 네이티브 바이너리 불일치 위험이 있어 미적용
            // WebP는 MIME 타입 + 확장자 2단계 검증으로 충분하다고 판단하여 내용 검사 건너뜀
            String ext = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase()
                    : "";

            if (!ext.equals(".webp")) {
                try (InputStream is = file.getInputStream()) {
                    BufferedImage image = ImageIO.read(is);
                    if (image == null) {
                        throw new IllegalArgumentException(
                                "유효하지 않은 이미지 파일입니다: " + originalFilename + ". 실제 이미지 데이터가 아닙니다."
                        );
                    }
                } catch (IllegalArgumentException e) {
                    throw e; // 위에서 던진 예외는 그대로 전파
                } catch (IOException e) {
                    throw new IllegalArgumentException("이미지 파일을 읽는 중 오류가 발생했습니다: " + originalFilename, e);
                }
            }
        }

        // JSON 파싱 시도 - 형식 오류는 명확한 예외로 변환
        List<PhotoMetaDto> metaList;
        try {
            metaList = objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            // JSON 파싱 실패 → 400 Bad Request
            throw new IllegalArgumentException(
                    "메타데이터 형식이 올바르지 않습니다. JSON 배열 형식이어야 합니다. " +
                            "예시: [{\"originalName\":\"photo1.jpg\",\"takenAt\":\"2025-11-12T10:00:00\",\"latitude\":35.1584,\"longitude\":129.1603}]",
                    e
            );
        }

        if (files.size() != metaList.size()) {
            throw new IllegalArgumentException("파일 개수와 메타데이터 개수가 일치하지 않습니다.");
        }

        long totalStart = System.currentTimeMillis();
        log.info("===== 사진 업로드 시작: 총 {}장 =====", files.size());

        // ✅ 비동기 병렬처리: GCS 업로드 + 카카오 API를 모든 사진 동시에 실행
        List<CompletableFuture<Photo>> futures = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            final int index = i;
            final MultipartFile file = files.get(i);
            final PhotoMetaDto meta = metaList.get(i);

            CompletableFuture<Photo> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // Semaphore 획득: 최대 3장만 동시 실행, 나머지는 대기
                    uploadSemaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("업로드 대기 중 인터럽트", e);
                }
                try {
                    log.info("----- 사진 {}/{} 처리 시작 (병렬) -----", index + 1, files.size());

                    // 1. GCS 업로드 (여러 사진 동시 실행)
                    long gcsStart = System.currentTimeMillis();
                    String gcsUrl = gcsService.uploadFile(file);
                    log.info("[{}번] GCS 업로드 소요시간: {}ms", index + 1, System.currentTimeMillis() - gcsStart);

                    // 2. 좌표 처리
                    BigDecimal lat = meta.getLatitude() != null
                            ? BigDecimal.valueOf(meta.getLatitude()) : null;
                    BigDecimal lng = meta.getLongitude() != null
                            ? BigDecimal.valueOf(meta.getLongitude()) : null;

                    // 3. 카카오 역지오코딩 (캐싱 적용되어 있어 같은 지역이면 즉시 반환)
                    String region = null;
                    if (lat != null && lng != null) {
                        long kakaoStart = System.currentTimeMillis();
                        region = geoCodingService.getDistrictFromCoordinates(lat, lng);
                        log.info("[{}번] 카카오 역지오코딩 소요시간: {}ms", index + 1, System.currentTimeMillis() - kakaoStart);
                    }

                    // 4. 촬영 시간 파싱
                    LocalDateTime takenAt = parseTakenAt(meta.getTakenAt());

                    // 5. Photo 엔티티 생성 (DB 저장은 아직 안 함)
                    return Photo.builder()
                            .user(user)
                            .filePath(gcsUrl)
                            .originalName(meta.getOriginalName())
                            .takenAt(takenAt)
                            .latitude(lat)
                            .longitude(lng)
                            .region(region)
                            .build();

                } catch (IOException e) {
                    throw new RuntimeException(index + 1 + "번 사진 처리 실패", e);
                } finally {
                    uploadSemaphore.release(); // 반드시 반환 (예외 발생해도)
                }
            });

            futures.add(future);
        }

        //  모든 병렬 작업 완료 대기 후 DB에 순차 저장
        // (DB 저장은 @Transactional이 메인 스레드에서 동작하므로 여기서 처리)
        List<Photo> uploadedPhotos = new ArrayList<>(); // GCS 업로드 완료된 사진들 추적
        List<Photo> savedPhotos = new ArrayList<>();

        try {
            for (CompletableFuture<Photo> future : futures) {
                Photo photo = future.join(); // 각 작업 완료될 때까지 대기
                uploadedPhotos.add(photo);  // GCS 업로드 완료 목록에 추가

                long dbStart = System.currentTimeMillis();
                Photo saved = photoRepository.save(photo);
                log.info("DB 저장 소요시간: {}ms", System.currentTimeMillis() - dbStart);
                savedPhotos.add(saved);
            }
        } catch (Exception e) {
            // DB 저장 실패 시 이미 GCS에 올라간 파일들 모두 삭제
            log.error("❌ DB 저장 실패, GCS 파일 롤백 시작 - 삭제 대상: {}장", uploadedPhotos.size());
            for (Photo photo : uploadedPhotos) {
                try {
                    gcsService.deleteFile(photo.getFilePath());
                    log.info("🗑️ GCS 롤백 삭제: {}", photo.getFilePath());
                } catch (Exception deleteException) {
                    log.error("❌ GCS 롤백 삭제 실패: {}", photo.getFilePath(), deleteException);
                }
            }
            throw e; // 예외 다시 던져서 트랜잭션 롤백
        }

        log.info("===== 사진 업로드 완료: 총 소요시간 {}ms =====", System.currentTimeMillis() - totalStart);

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
    @Transactional
    public Long createComment(Long photoId, TravelDto.CommentRequest request, User user) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사진입니다."));

        // 권한 검증 제거 (누구나 댓글 작성 가능)
        Comment cment = Comment.builder()
                .photo(photo)
                .writer(user)  // ⭐ 작성자 저장
                .content(request.getContent())
                .build();

        return commentRepository.save(cment).getId();
    }

    // 사진 ID로 댓글 수정
    @Transactional
    public void updateCommentByPhotoId(Long photoId, TravelDto.CommentRequest request, User user) {
        // photoId로 댓글 찾기
        Comment cment = commentRepository.findByPhotoId(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진에 댓글이 존재하지 않습니다."));

        // 작성자 본인 확인
        if (!cment.getWriter().getId().equals(user.getId())) {
            throw new SecurityException("본인의 댓글만 수정할 수 있습니다.");
        }

        // 내용 수정
        cment.updateContent(request.getContent());
    }

    // 사진 ID로 댓글 삭제
    @Transactional
    public void deleteCommentByPhotoId(Long photoId, User user) {
        // photoId로 댓글 찾기
        Comment cment = commentRepository.findByPhotoId(photoId)
                .orElseThrow(() -> new IllegalArgumentException("해당 사진에 댓글이 존재하지 않습니다."));

        // 권한 확인
        boolean isWriter = cment.getWriter().getId().equals(user.getId());
        boolean isPhotoOwner = cment.getPhoto().getUser().getId().equals(user.getId());

        if (!isWriter && !isPhotoOwner) {
            throw new SecurityException("댓글을 삭제할 권한이 없습니다.");
        }

        // 삭제
        commentRepository.delete(cment);
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
     * 본인 사진만 조회
     */
    @Transactional(readOnly = true)
    public List<Photo> getMyPhotos(Long userId) {
        return photoRepository.findByUserId(userId);
    }

    /**
     * 특정 사진 조회
     */
    @Transactional(readOnly = true)
    public Photo getPhotoById(Long id) {
        return photoRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("사진을 찾을 수 없습니다. ID: " + id));
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

        // 권한 검증: photo.getUser()로 직접 소유자 확인 (TravelDay 여부 무관)
        if (!photo.getUser().getId().equals(currentUserId)) {
            throw new SecurityException("사진을 이동할 권한이 없습니다.");
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
