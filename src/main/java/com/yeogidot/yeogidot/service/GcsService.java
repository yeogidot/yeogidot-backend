package com.yeogidot.yeogidot.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Google Cloud Storage 파일 업로드 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GcsService {
    
    @Value("${spring.cloud.gcp.storage.bucket}")
    private String bucketName;

    private final Storage storage;

    /**
     * GCS에 파일 업로드 후 공개 URL 반환
     */
    public String uploadFile(MultipartFile file) throws IOException {
        // 파일명 중복 방지를 위한 UUID 생성
        String uuid = UUID.randomUUID().toString();
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        
        String fileName = uuid + extension;

        log.info("📤 GCS 업로드 시작: {} → {}", originalFilename, fileName);

        // 이미지 압축 (JPEG/PNG만 압축, 나머지는 원본 그대로)
        byte[] uploadBytes = compressIfImage(file);
        log.info("📦 파일 크기: {}KB → {}KB", file.getSize() / 1024, uploadBytes.length / 1024);

        // GCS에 저장할 파일 정보 설정
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, fileName)
                .setContentType(file.getContentType())
                .build();

        // 파일 업로드
        Blob blob = storage.create(blobInfo, uploadBytes);

        // 공개 URL 반환
        String publicUrl = String.format("https://storage.googleapis.com/%s/%s", bucketName, fileName);

        log.info("✅ GCS 업로드 완료: {}", publicUrl);

        return publicUrl;
    }

    /**
     * 이미지 압축 (JPEG/PNG → 품질 90%로 압축)
     * - 화질 차이 거의 없음 (0.9 품질)
     * - 파일 크기 40~60% 감소 효과
     * - JPEG/PNG 외 포맷(GIF, WebP 등)은 원본 그대로 반환
     */
    private byte[] compressIfImage(MultipartFile file) throws IOException {
        String contentType = file.getContentType();

        // JPEG, PNG만 압축 (나머지는 원본 반환)
        if (contentType == null ||
                (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            return file.getBytes();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Thumbnails.of(file.getInputStream())
                .scale(1.0)           // 크기 변경 없음 (원본 해상도 유지)
                .outputQuality(0.9)   // 품질 90% (육안으로 차이 거의 없음)
                .outputFormat("JPEG") // JPEG으로 출력
                .toOutputStream(outputStream);

        return outputStream.toByteArray();
    }

    public void deleteFile(String fileUrl) {
        // fileUrl 예시: https://storage.googleapis.com/버킷이름/folder/uuid.jpg
        // lastIndexOf("/")는 파일명만 추출하므로 서브 경로(folder/uuid.jpg)가 있으면 GCS에서 찾지 못함
        // → 버킷명 이후의 전체 객체 경로를 추출해야 정확히 삭제됨
        String prefix = "https://storage.googleapis.com/" + bucketName + "/";
        if (!fileUrl.startsWith(prefix)) {
            log.warn("GCS 파일 URL 형식이 올바르지 않습니다: {}", fileUrl);
            return;
        }
        String objectName = fileUrl.substring(prefix.length());

        log.info("GCS 파일 삭제 시도: {}", objectName);

        BlobId blobId = BlobId.of(bucketName, objectName);
        boolean deleted = storage.delete(blobId);

        if (deleted) {
            log.info("GCS 파일 삭제 완료: {}", objectName);
        } else {
            log.warn("GCS 파일을 찾을 수 없거나 삭제 실패: {}", objectName);
        }
    }
}
