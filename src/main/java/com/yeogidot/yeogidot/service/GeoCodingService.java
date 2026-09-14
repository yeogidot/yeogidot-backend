package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 카카오 역지오코딩 서비스
 * 위도/경도 → 지역명 변환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeoCodingService {

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    private final RestTemplate restTemplate;
    private final CacheManager cacheManager;

    // 이 서비스 인스턴스에서 진행 중인 좌표별 요청만 보관한다. 완료되면 제거한다.
    private final ConcurrentMap<String, CompletableFuture<RegionInfo>> inFlight = new ConcurrentHashMap<>();

    /**
     * 위도/경도로 지역명 조회 (시/도 단위)
     *
     * @param latitude  위도
     * @param longitude 경도
     * @return 지역명 (예: "부산광역시", "제주특별자치도")
     */
    public String getRegionFromCoordinates(BigDecimal latitude, BigDecimal longitude) {
        RegionInfo regionInfo = getDetailedRegion(latitude, longitude);
        return regionInfo != null ? regionInfo.getRegion1depth() : null;
    }

    /**
     * 위도/경도로 시/군/구 레벨의 지역명 조회
     *
     * @param latitude  위도
     * @param longitude 경도
     * @return 시/군/구 레벨 지역명 (예: "부산광역시 부산진구", "서울특별시 강남구")
     */
    public String getDistrictFromCoordinates(BigDecimal latitude, BigDecimal longitude) {
        RegionInfo regionInfo = getDetailedRegion(latitude, longitude);
        if (regionInfo != null) {
            String region1 = regionInfo.getRegion1depth();  // 예: "부산광역시"
            String region2 = regionInfo.getRegion2depth();  // 예: "부산진구"

            if (region1 != null && region2 != null) {
                // "부산광역시 부산진구" 형태로 반환
                return region1 + " " + region2;
            } else if (region2 != null) {
                // region2만 있는 경우
                return region2;
            } else if (region1 != null) {
                // region1만 있는 경우
                return region1;
            }
        }
        return null;
    }

    /**
     * 위도/경도로 상세 지역 정보 조회
     *
     * @param latitude  위도
     * @param longitude 경도
     * @return RegionInfo (시/도, 구/군 포함)
     */
    public RegionInfo getDetailedRegion(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }

        // 기존 소수점 4자리 반올림 정책과 상세 캐시 키를 유지한다.
        String key = "detail:" + latitude.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + "," + longitude.setScale(4, RoundingMode.HALF_UP).toPlainString();
        Cache cache = Objects.requireNonNull(cacheManager.getCache("geocoding"));
        RegionInfo cached = cache.get(key, RegionInfo.class);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<RegionInfo> pending = new CompletableFuture<>();
        CompletableFuture<RegionInfo> existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) {
            return awaitRegion(existing);
        }

        try {
            // 첫 캐시 확인 직후 다른 요청이 완료됐을 수 있어 다시 확인한다.
            RegionInfo result = cache.get(key, RegionInfo.class);
            if (result == null) {
                result = requestRegion(latitude, longitude);
                if (result != null) {
                    cache.put(key, result);
                }
            }
            pending.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } finally {
            // 실패도 보관하지 않는다. 다음 요청이 새 조회를 시도할 수 있다.
            inFlight.remove(key, pending);
        }
    }

    private RegionInfo awaitRegion(CompletableFuture<RegionInfo> pending) {
        try {
            return pending.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("지역 정보 조회 대기 중 인터럽트", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("지역 정보 조회 대기 시간 초과", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("지역 정보 조회 실패", exception.getCause());
        }
    }

    private RegionInfo requestRegion(BigDecimal latitude, BigDecimal longitude) {

        try {
            String url = String.format(
                    "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=%s&y=%s",
                    longitude.toString(),
                    latitude.toString()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("documents")) {
                Object documentsObj = body.get("documents");
                if (!(documentsObj instanceof java.util.List<?>)) return null;
                java.util.List<?> documents = (java.util.List<?>) documentsObj;

                if (!documents.isEmpty()) {
                    Object firstDocObj = documents.get(0);
                    if (!(firstDocObj instanceof Map<?, ?> firstDoc)) return null;
                    String region1depth = firstDoc.get("region_1depth_name") instanceof String s ? s : null;
                    String region2depth = firstDoc.get("region_2depth_name") instanceof String s ? s : null;

                    log.info("📍 역지오코딩 성공: ({}, {}) → {} {}", latitude, longitude, region1depth, region2depth);
                    return new RegionInfo(region1depth, region2depth);
                }
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.error("🚨 카카오 API 일일 쿼터 초과. 오늘 더 이상 역지오코딩 불가.");
                throw new RuntimeException("카카오 API 일일 사용량을 초과했습니다. 잠시 후 다시 시도해주세요.");
            }
            log.error("❌ 역지오코딩 실패 (HTTP {}): ({}, {}) - {}", e.getStatusCode(), latitude, longitude, e.getMessage());
        } catch (Exception e) {
            log.error("❌ 역지오코딩 실패: ({}, {}) - {}", latitude, longitude, e.getMessage());
        }

        return null;
    }

    /**
     * 지역 정보를 담는 내부 클래스
     */
    public static class RegionInfo implements java.io.Serializable {
        private final String region1depth; // 시/도
        private final String region2depth; // 구/군

        public RegionInfo(String region1depth, String region2depth) {
            this.region1depth = region1depth;
            this.region2depth = region2depth;
        }

        public String getRegion1depth() {
            return region1depth;
        }

        public String getRegion2depth() {
            return region2depth;
        }
    }
}
