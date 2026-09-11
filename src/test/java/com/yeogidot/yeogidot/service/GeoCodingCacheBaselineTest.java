package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(GeoCodingCacheBaselineTest.TestConfig.class)
@ContextConfiguration
@TestPropertySource(properties = "kakao.api.key=test-key")
class GeoCodingCacheBaselineTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.497900");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.027600");

    @Autowired
    private GeoCodingService geoCodingService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(cacheManager.getCache("geocoding")).clear();
        reset(restTemplate);
        stubSuccessfulKakaoResponse();
    }

    @Test
    @DisplayName("같은 조회 메서드를 같은 좌표로 반복하면 기존 캐시를 사용한다")
    void sameMethodAndCoordinateUsesExistingCache() {
        String first = geoCodingService.getDistrictFromCoordinates(LATITUDE, LONGITUDE);
        String second = geoCodingService.getDistrictFromCoordinates(LATITUDE, LONGITUDE);

        assertEquals("서울특별시 강남구", first);
        assertEquals(first, second);
        verify(restTemplate, times(1)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        );
    }

    @Test
    @DisplayName("같은 좌표라도 세 조회 메서드의 캐시 결과는 공유되지 않는다")
    void differentLookupMethodsDoNotShareCachedRegionInfo() {
        String district = geoCodingService.getDistrictFromCoordinates(LATITUDE, LONGITUDE);
        String region = geoCodingService.getRegionFromCoordinates(LATITUDE, LONGITUDE);
        GeoCodingService.RegionInfo detail = geoCodingService.getDetailedRegion(LATITUDE, LONGITUDE);

        assertEquals("서울특별시 강남구", district);
        assertEquals("서울특별시", region);
        assertEquals("서울특별시", detail.getRegion1depth());
        assertEquals("강남구", detail.getRegion2depth());
        verify(restTemplate, times(3)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        );
    }

    @Test
    @DisplayName("빈 캐시에 같은 좌표 요청이 동시에 들어오면 외부 호출이 중복된다")
    void concurrentCacheMissesCallExternalApiMoreThanOnce() throws Exception {
        int requestCount = 3;
        CountDownLatch startRequests = new CountDownLatch(1);
        CountDownLatch enteredExternalApi = new CountDownLatch(requestCount);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenAnswer(invocation -> {
            enteredExternalApi.countDown();
            if (!releaseResponses.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 외부 호출 대기 시간 초과");
            }
            return kakaoResponse();
        });

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    startRequests.await();
                    return geoCodingService.getDistrictFromCoordinates(LATITUDE, LONGITUDE);
                }));
            }

            startRequests.countDown();
            assertTrue(
                    enteredExternalApi.await(5, TimeUnit.SECONDS),
                    "동일 좌표 요청 세 개가 모두 외부 API 호출까지 진입해야 한다"
            );
            releaseResponses.countDown();

            for (Future<String> future : futures) {
                assertEquals("서울특별시 강남구", future.get(5, TimeUnit.SECONDS));
            }

            verify(restTemplate, times(requestCount)).exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            );
        } finally {
            releaseResponses.countDown();
            executor.shutdownNow();
        }
    }

    private void stubSuccessfulKakaoResponse() {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(kakaoResponse());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> kakaoResponse() {
        Map body = Map.of(
                "documents", List.of(Map.of(
                        "region_1depth_name", "서울특별시",
                        "region_2depth_name", "강남구"
                ))
        );
        return ResponseEntity.ok(body);
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("geocoding");
        }

        @Bean
        RestTemplate restTemplate() {
            return mock(RestTemplate.class);
        }

        @Bean
        GeoCodingService geoCodingService(RestTemplate restTemplate) {
            return new GeoCodingService(restTemplate);
        }
    }
}
