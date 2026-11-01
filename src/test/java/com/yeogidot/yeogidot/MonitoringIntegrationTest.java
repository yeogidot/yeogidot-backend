package com.yeogidot.yeogidot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=classpath:context-test.properties,classpath:application-observability.properties",
        "management.server.port=0",
        "spring.datasource.url=jdbc:h2:mem:monitoring-test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("observability")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class MonitoringIntegrationTest {
    @MockitoBean S3Client s3Client;
    @MockitoBean RestTemplate restTemplate;
    @MockitoBean StringRedisTemplate redisTemplate;
    @Autowired TestRestTemplate http;
    @Autowired Environment environment;
    @LocalServerPort int appPort;
    @LocalManagementPort int metricsPort;

    @Test
    void metricsAreCollectedOnSeparateLoopbackListenerAndProtectedOnAppPort() {
        assertThat(appPort).isNotEqualTo(metricsPort);
        var app = "http://127.0.0.1:" + appPort;
        var metrics = "http://127.0.0.1:" + metricsPort;
        assertThat(http.getForEntity(app + "/health/ready", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange(app + "/health/ready", HttpMethod.HEAD, null, String.class)
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> response = http.getForEntity(metrics + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes", "http_server_requests_seconds_count",
                "hikaricp_connections", "application=\"yeogidot\"");
        assertThat(http.getForEntity(app + "/actuator/prometheus", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity(app + "/api/auth/me", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity(metrics + "/actuator/env", String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(http.postForEntity(metrics + "/actuator/prometheus", null, String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        verifyNoInteractions(s3Client, restTemplate, redisTemplate);
    }
}
