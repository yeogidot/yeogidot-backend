package com.yeogidot.yeogidot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Cloudflare R2 Storage 설정 (S3 호환 API 사용)
 */
@Slf4j
@Configuration
public class GcsConfig {

    @Value("${r2.access-key}")
    private String accessKey;

    @Value("${r2.secret-key}")
    private String secretKey;

    @Value("${r2.account-id}")
    private String accountId;

    @Bean
    public S3Client s3Client() {
        log.info("🔧 R2 S3Client 초기화 시작");

        String endpoint = String.format("https://%s.r2.cloudflarestorage.com", accountId);

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .region(Region.of("auto"))
                .build();

        log.info("✅ R2 S3Client 초기화 완료 - endpoint: {}", endpoint);
        return client;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
