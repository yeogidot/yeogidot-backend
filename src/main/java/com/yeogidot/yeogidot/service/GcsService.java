package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GcsService {

    // Storage Bean 주석 처리
    // private final com.google.cloud.storage.Storage storage;
    // private String bucketName = "test-bucket";

    public String uploadFile(MultipartFile file) throws IOException {

        // 원래 코드 주석 처리
        /*
        String uuid = UUID.randomUUID().toString();
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, uuid)
                .setContentType(file.getContentType())
                .build();
        storage.create(blobInfo, file.getBytes());
        return "https://storage.googleapis.com/" + bucketName + "/" + uuid;
        */

        // 임시) 서버 실행 확인용 가짜 URL 반환
        String fakeUrl = "https://fake-gcs-url.com/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        System.out.println(">>> [TEST MODE] GCS 업로드 흉내냄: " + fakeUrl);
        return fakeUrl;
    }
}
