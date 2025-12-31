package com.yeogidot.yeogidot.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

        // GCS에 저장할 파일 정보 설정
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, fileName)
                .setContentType(file.getContentType())
                .build();

        // 파일 업로드
        Blob blob = storage.create(blobInfo, file.getBytes());

        // 공개 URL 반환
        String publicUrl = String.format("https://storage.googleapis.com/%s/%s", bucketName, fileName);
        
        log.info("✅ GCS 업로드 완료: {}", publicUrl);
        
        return publicUrl;
    }
}
