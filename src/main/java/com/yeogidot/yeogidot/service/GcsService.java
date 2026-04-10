package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Cloudflare R2 Storage 파일 업로드 서비스 (S3 호환 API)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GcsService {

    @Value("${r2.bucket}")
    private String bucketName;

    @Value("${r2.public-url}")
    private String publicUrl;

    private final S3Client s3Client;

    /**
     * R2에 파일 업로드 후 퍼블릭 URL 반환
     */
    public String uploadFile(MultipartFile file) throws IOException {
        String uuid = UUID.randomUUID().toString();
        String originalFilename = file.getOriginalFilename();
        String extension = "";

        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String fileName = uuid + extension;

        log.info("📤 R2 업로드 시작: {} → {}", originalFilename, fileName);

        byte[] uploadBytes = compressIfImage(file);
        log.info("📦 파일 크기: {}KB → {}KB", file.getSize() / 1024, uploadBytes.length / 1024);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(uploadBytes));

        String fileUrl = publicUrl + "/" + fileName;
        log.info("✅ R2 업로드 완료: {}", fileUrl);

        return fileUrl;
    }

    /**
     * 이미지 압축 (JPEG/PNG → 품질 90%로 압축)
     */
    private byte[] compressIfImage(MultipartFile file) throws IOException {
        String contentType = file.getContentType();

        if (contentType == null ||
                (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            return file.getBytes();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Thumbnails.of(file.getInputStream())
                .scale(1.0)
                .outputQuality(0.9)
                .outputFormat("JPEG")
                .toOutputStream(outputStream);

        return outputStream.toByteArray();
    }

    /**
     * R2에서 파일 삭제
     */
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(publicUrl)) {
            log.warn("R2 파일 URL 형식이 올바르지 않습니다: {}", fileUrl);
            return;
        }

        String objectName = fileUrl.substring(publicUrl.length() + 1);

        log.info("R2 파일 삭제 시도: {}", objectName);

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build();

        s3Client.deleteObject(deleteRequest);
        log.info("R2 파일 삭제 완료: {}", objectName);
    }
}
