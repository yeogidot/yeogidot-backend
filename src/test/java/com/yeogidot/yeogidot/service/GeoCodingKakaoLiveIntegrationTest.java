package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.config.GcsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 카카오 API + 별도 로컬 Redis를 사용하는 소규모 연동 확인. 성능 벤치마크가 아니다.
 * RUN_KAKAO_LIVE_TEST=true, KAKAO_API_KEY, GEOCODING_TEST_REDIS_PORT 설정 필요.
 * 실행: gradlew test --tests '*GeoCodingKakaoLiveIntegrationTest' --rerun-tasks
 * 정상 실행 시 총 외부 요청 4회. 인증 키와 운영 설정 파일은 코드/로그에 기록하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_KAKAO_LIVE_TEST", matches = "true")
@SpringJUnitConfig(GeoCodingKakaoLiveIntegrationTest.LiveConfig.class)
class GeoCodingKakaoLiveIntegrationTest {
    private static final String PREFIX = "yeogidot-kakao-live-test-" + UUID.randomUUID() + "::";
    private static final BigDecimal SEOUL_LATITUDE = new BigDecimal("37.5665");
    private static final BigDecimal SEOUL_LONGITUDE = new BigDecimal("126.9780");

    @Autowired private GeoCodingService service;
    @Autowired private CacheManager manager;
    @Autowired private AtomicInteger outboundCalls;
    private int callsBefore;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String key = System.getenv("KAKAO_API_KEY");
        if (key == null || key.isBlank() || key.startsWith("${")) {
            throw new IllegalStateException("KAKAO_API_KEY 환경 변수에 테스트에 사용할 REST API 키를 지정하세요.");
        }
        String portValue = System.getenv("GEOCODING_TEST_REDIS_PORT");
        if (portValue == null) throw new IllegalStateException("GEOCODING_TEST_REDIS_PORT가 필요합니다.");
        int port = Integer.parseInt(portValue);
        if (port < 1024 || port > 65535 || port == 6379) {
            throw new IllegalArgumentException("6379 대신 별도의 테스트 Redis 포트를 지정하세요.");
        }
        registry.add("kakao.api.key", () -> key);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> port);
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.cache.redis.time-to-live", () -> "86400000");
        registry.add("spring.cache.redis.key-prefix", () -> PREFIX);
        registry.add("spring.cache.redis.use-key-prefix", () -> "true");
    }

    @BeforeEach
    void clearOnlyTestCache() {
        assertInstanceOf(RedisCacheManager.class, manager);
        Objects.requireNonNull(manager.getCache("geocoding")).clear();
        callsBefore = outboundCalls.get();
    }

    @Test
    void actualCoordinatesReturnSeoulAndBusanAndWarmCacheAvoidsHttp() {
        GeoCodingService.RegionInfo seoul = service.getDetailedRegion(SEOUL_LATITUDE, SEOUL_LONGITUDE);
        GeoCodingService.RegionInfo busan = service.getDetailedRegion(
                new BigDecimal("35.1796"), new BigDecimal("129.0756"));
        assertRegion(seoul, "서울특별시");
        assertRegion(busan, "부산광역시");
        assertEquals(seoul.getRegion1depth(), service.getRegionFromCoordinates(SEOUL_LATITUDE, SEOUL_LONGITUDE));
        assertEquals(busan.getRegion1depth(), service.getRegionFromCoordinates(
                new BigDecimal("35.1796"), new BigDecimal("129.0756")));
        verifyAndReportCalls("two coordinates + warm cache", 2);
    }

    @Test
    void threeFormatsOfSameCoordinateMakeOneRealHttpCall() {
        String district = service.getDistrictFromCoordinates(SEOUL_LATITUDE, SEOUL_LONGITUDE);
        String province = service.getRegionFromCoordinates(SEOUL_LATITUDE, SEOUL_LONGITUDE);
        GeoCodingService.RegionInfo detail = service.getDetailedRegion(SEOUL_LATITUDE, SEOUL_LONGITUDE);
        assertRegion(detail, "서울특별시");
        assertEquals(detail.getRegion1depth(), province);
        assertEquals(detail.getRegion1depth() + " " + detail.getRegion2depth(), district);
        verifyAndReportCalls("three formats / one coordinate", 1);
    }

    @Test
    void simultaneousRequestsMakeOneRealHttpCall() throws Exception {
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timeout");
                    return service.getDistrictFromCoordinates(SEOUL_LATITUDE, SEOUL_LONGITUDE);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String expected = results.get(0).get(15, TimeUnit.SECONDS);
            assertNotNull(expected);
            assertTrue(expected.startsWith("서울특별시 "));
            for (Future<String> result : results) assertEquals(expected, result.get(15, TimeUnit.SECONDS));
            verifyAndReportCalls("three simultaneous requests / one coordinate", 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private static void assertRegion(GeoCodingService.RegionInfo region, String expectedProvince) {
        assertNotNull(region, "실제 카카오 지역 정보를 받지 못했습니다. API 권한/연결을 확인하세요.");
        assertEquals(expectedProvince, region.getRegion1depth());
        assertNotNull(region.getRegion2depth());
        assertFalse(region.getRegion2depth().isBlank());
    }

    private void verifyAndReportCalls(String scenario, int expected) {
        int actual = outboundCalls.get() - callsBefore;
        assertEquals(expected, actual);
        System.out.printf("KAKAO_LIVE: %s -> HTTP calls=%d%n", scenario, actual);
    }

    @AfterAll
    static void cleanup(@Autowired CacheManager manager, @Autowired AtomicInteger outboundCalls) {
        Objects.requireNonNull(manager.getCache("geocoding")).clear();
        System.out.printf("KAKAO_LIVE: total HTTP attempts=%d%n", outboundCalls.get());
    }

    @Configuration
    @EnableCaching
    @ImportAutoConfiguration({RedisAutoConfiguration.class, CacheAutoConfiguration.class})
    static class LiveConfig {
        @Bean AtomicInteger outboundCalls() { return new AtomicInteger(); }

        @Bean RestTemplate restTemplate(AtomicInteger outboundCalls) {
            // 운영과 같은 HTTP 연결/읽기 제한을 사용하되 R2 Bean은 로드하지 않는다.
            RestTemplate http = new GcsConfig().restTemplate();
            http.getInterceptors().add((request, body, execution) -> {
                if (!"https".equals(request.getURI().getScheme())
                        || !"dapi.kakao.com".equals(request.getURI().getHost())
                        || !"/v2/local/geo/coord2regioncode.json".equals(request.getURI().getPath())) {
                    throw new IllegalStateException("허용된 카카오 지역 조회 경로만 사용할 수 있습니다.");
                }
                if (outboundCalls.incrementAndGet() > 8) {
                    throw new IllegalStateException("실제 API 테스트의 요청 상한 8회를 초과했습니다.");
                }
                return execution.execute(request, body);
            });
            return http;
        }

        @Bean GeoCodingService geoCodingService(RestTemplate http, CacheManager manager) {
            return new GeoCodingService(http, manager);
        }
    }
}
