package com.yeogidot.yeogidot.service;

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
        String uuid = UUID.randomUUID().toString();
        String ext = file.getContentType();
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, uuid).setContentType(ext).build();
        storage.create(blobInfo, file.getBytes());
        return "https://storage.googleapis.com/" + bucketName + "/" + uuid;
    }
}