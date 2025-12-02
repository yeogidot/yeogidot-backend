package com.yeogidot.yeogidot.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GcsService {

    @Value("${spring.cloud.gcp.storage.bucket}")
    private String bucketName;

    private final Storage storage;

    public String uploadFile(MultipartFile file) throws IOException {
        // 파일명 중복 방지를 위한 UUID 생성
        String uuid = UUID.randomUUID().toString();
        String ext = file.getContentType(); // 이미지 타입 (image/png 등)

        // GCS에 저장할 파일 정보 설정
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, uuid)
                .setContentType(ext)
                .build();

        // 실제 전송
        storage.create(blobInfo, file.getBytes());

        // 저장된 이미지의 URL 반환
        return "https://storage.googleapis.com/" + bucketName + "/" + uuid;
    }
}