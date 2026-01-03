package com.yeogidot.yeogidot.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * Google Cloud Storage 설정
 */
@Slf4j
@Configuration // GCS 키 등록시 주석 해제
public class GcsConfig {

    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;

    @Bean
    public Storage storage() throws IOException {
        log.info("🔧 GCS Storage Bean 초기화 시작");
        log.info("📁 Credentials 위치: class path resource [gcs-key.json]");
        log.info("🆔 Project ID: {}", projectId);
        
        Storage storage = StorageOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(GoogleCredentials.fromStream(
                        new ClassPathResource("gcs-key.json").getInputStream()))
                .build()
                .getService();
        
        log.info("✅ GCS Storage Bean 초기화 완료");
        return storage;
    }
}
