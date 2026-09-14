package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 기준 커밋의 조건을 개선 후 회귀 테스트로 유지한다.
 * 실제 서비스 Bean + 메모리 캐시 + Mock HTTP를 사용하며 Redis 실측은 아니다.
 */
@SpringJUnitConfig(GeoCodingCacheBaselineTest.TestConfig.class)
@TestPropertySource(properties = "kakao.api.key=test-key")
class GeoCodingCacheBaselineTest {
    private static final BigDecimal LATITUDE = new BigDecimal("37.497900");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.027600");

    @Autowired private GeoCodingService service;
    @Autowired private RestTemplate http;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(cacheManager.getCache("geocoding")).clear();
        reset(http);
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response("서울특별시", "강남구"));
    }

    @Test
    void sameMethodAndCoordinateUsesExistingCache() {
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        verifyCalls(1);
    }

    @Test
    void threeMethodsShareOneDetailedResult() {
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("서울특별시", service.getRegionFromCoordinates(LATITUDE, LONGITUDE));
        GeoCodingService.RegionInfo detail = service.getDetailedRegion(LATITUDE, LONGITUDE);
        assertEquals("서울특별시", detail.getRegion1depth());
        assertEquals("강남구", detail.getRegion2depth());
        verifyCalls(1);
    }

    @Test
    void detailFirstAlsoSuppliesOtherFormats() {
        service.getDetailedRegion(LATITUDE, LONGITUDE);
        assertEquals("서울특별시", service.getRegionFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        verifyCalls(1);
    }

    @Test
    void differentCoordinatesKeepDifferentResults() {
        BigDecimal otherLatitude = new BigDecimal("35.1796");
        when(http.exchange(contains("y=35.1796"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response("부산광역시", "중구"));
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("부산광역시 중구", service.getDistrictFromCoordinates(otherLatitude, LONGITUDE));
        assertEquals("서울특별시", service.getRegionFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("부산광역시", service.getRegionFromCoordinates(otherLatitude, LONGITUDE));
        verifyCalls(2);
    }

    @Test
    void existingFourDecimalRoundingPolicyIsPreserved() {
        service.getDetailedRegion(LATITUDE, LONGITUDE);
        service.getDetailedRegion(new BigDecimal("37.49791"), new BigDecimal("127.02761"));
        verifyCalls(1);
    }

    @Test
    void missingCoordinatesDoNotCallExternalApi() {
        assertNull(service.getRegionFromCoordinates(null, LONGITUDE));
        assertNull(service.getDistrictFromCoordinates(LATITUDE, null));
        assertNull(service.getDetailedRegion(null, null));
        verifyCalls(0);
    }

    @Test
    void emptyResponseIsNotCachedAndCanBeRetried() {
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("documents", List.of())))
                .thenReturn(response("서울특별시", "강남구"));
        assertNull(service.getDetailedRegion(LATITUDE, LONGITUDE));
        assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        service.getRegionFromCoordinates(LATITUDE, LONGITUDE);
        verifyCalls(2);
    }

    @Test
    void concurrentRequestsShareOneSuccessfulCall() throws Exception {
        concurrentRequests(null, false);
    }

    @Test
    void concurrentDifferentFormatsShareOneSuccessfulCall() throws Exception {
        concurrentRequests(null, true);
    }

    @Test
    void concurrentHttp429FailureReleasesWaitersAndAllowsRetry() throws Exception {
        concurrentRequests(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS), false);
    }

    @Test
    void concurrentNetworkTimeoutReturnsNullAndAllowsRetry() throws Exception {
        concurrentRequests(new ResourceAccessException("simulated read timeout"), false);
    }

    @Test
    void differentCoordinatesCanLoadInParallel() throws Exception {
        CountDownLatch enteredHttp = new CountDownLatch(2);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    enteredHttp.countDown();
                    if (!releaseHttp.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release HTTP response");
                    }
                    String url = invocation.getArgument(0);
                    return url.contains("y=35.1796") ? response("부산광역시", "중구")
                            : response("서울특별시", "강남구");
                });
        try {
            Future<String> seoul = executor.submit(() -> service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
            Future<String> busan = executor.submit(() -> service.getDistrictFromCoordinates(
                    new BigDecimal("35.1796"), LONGITUDE));
            assertTrue(enteredHttp.await(5, TimeUnit.SECONDS), "서로 다른 좌표는 동시에 조회해야 한다");
            releaseHttp.countDown();
            assertEquals("서울특별시 강남구", seoul.get(5, TimeUnit.SECONDS));
            assertEquals("부산광역시 중구", busan.get(5, TimeUnit.SECONDS));
            verifyCalls(2);
        } finally {
            releaseHttp.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void concurrentRequests(RuntimeException failure, boolean mixedFormats) throws Exception {
        CountDownLatch enteredHttp = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        List<Thread> threads = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "geocoding-test");
            threads.add(thread);
            return thread;
        });
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    enteredHttp.countDown();
                    if (!releaseHttp.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release HTTP response");
                    }
                    if (failure != null) throw failure;
                    return response("서울특별시", "강남구");
                });
        List<Future<Object>> results = new ArrayList<>();
        try {
            results.add(executor.submit(() -> service.getDistrictFromCoordinates(LATITUDE, LONGITUDE)));
            assertTrue(enteredHttp.await(5, TimeUnit.SECONDS));
            results.add(executor.submit(() -> mixedFormats
                    ? service.getRegionFromCoordinates(LATITUDE, LONGITUDE)
                    : service.getDistrictFromCoordinates(LATITUDE, LONGITUDE)));
            results.add(executor.submit(() -> mixedFormats
                    ? service.getDetailedRegion(LATITUDE, LONGITUDE)
                    : service.getDistrictFromCoordinates(LATITUDE, LONGITUDE)));

            // Redis 응답 대기를 공유 결과 대기로 오인하지 않도록 awaitRegion 진입까지 확인한다.
            await().atMost(Duration.ofSeconds(5)).until(() -> threads.size() == 3
                    && threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING
                            || thread.getState() == Thread.State.WAITING)
                    && threads.stream().filter(thread -> java.util.Arrays.stream(thread.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals(GeoCodingService.class.getName())
                                    && frame.getMethodName().equals("awaitRegion"))).count() == 2);
            assertTrue(results.stream().noneMatch(Future::isDone));
            verifyCalls(1);
            releaseHttp.countDown();

            for (int i = 0; i < results.size(); i++) {
                Future<Object> result = results.get(i);
                if (failure instanceof HttpClientErrorException) {
                    ExecutionException exception = assertThrows(ExecutionException.class,
                            () -> result.get(5, TimeUnit.SECONDS));
                    assertEquals("카카오 API 일일 사용량을 초과했습니다. 잠시 후 다시 시도해주세요.",
                            exception.getCause().getMessage());
                } else if (failure != null) {
                    assertNull(result.get(5, TimeUnit.SECONDS));
                } else if (mixedFormats && i == 2) {
                    GeoCodingService.RegionInfo detail = (GeoCodingService.RegionInfo) result.get(5, TimeUnit.SECONDS);
                    assertEquals("서울특별시", detail.getRegion1depth());
                    assertEquals("강남구", detail.getRegion2depth());
                } else {
                    assertEquals(mixedFormats && i == 1 ? "서울특별시" : "서울특별시 강남구",
                            result.get(5, TimeUnit.SECONDS));
                }
            }
            verifyCalls(1);
            when(http.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response("서울특별시", "강남구"));
            assertEquals("서울특별시 강남구", service.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
            verifyCalls(failure == null ? 1 : 2);
        } finally {
            releaseHttp.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void verifyCalls(int count) {
        verify(http, times(count)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @SuppressWarnings("rawtypes")
    private static ResponseEntity<Map> response(String region, String district) {
        return ResponseEntity.ok(Map.of("documents", List.of(Map.of(
                "region_1depth_name", region, "region_2depth_name", district))));
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager("geocoding"); }
        @Bean RestTemplate restTemplate() { return mock(RestTemplate.class); }
        @Bean GeoCodingService geoCodingService(RestTemplate http, CacheManager cacheManager) {
            return new GeoCodingService(http, cacheManager);
        }
    }
}
