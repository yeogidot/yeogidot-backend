package com.yeogidot.yeogidot.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

@Configuration
public class GcsConfig {

    @Bean
    public Storage storage() throws IOException {
        return StorageOptions.newBuilder()
                .setProjectId("intrepid-period-474816-e8") // 희철님 프로젝트 ID
                .setCredentials(GoogleCredentials.fromStream(
                        new ClassPathResource("gcs-key.json").getInputStream())) // 리소스 폴더의 키 파일 읽기
                .build()
                .getService();
    }
}