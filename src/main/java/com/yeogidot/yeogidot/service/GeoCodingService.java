package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

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

    /**
     * 위도/경도로 지역명 조회 (시/도 단위)
     *
     * @param latitude  위도
     * @param longitude 경도
     * @return 지역명 (예: "부산광역시", "제주특별자치도")
     */
    @Cacheable(
            value = "geocoding",
            key = "'region1:' + #latitude.setScale(4, T(java.math.RoundingMode).HALF_UP).toString() + ',' + #longitude.setScale(4, T(java.math.RoundingMode).HALF_UP).toString()"
    )
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
    @Cacheable(
            value = "geocoding",
            key = "#latitude.setScale(4, T(java.math.RoundingMode).HALF_UP).toString() + ',' + #longitude.setScale(4, T(java.math.RoundingMode).HALF_UP).toString()"
    )
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
        } catch (Exception e) {
            log.error("❌ 역지오코딩 실패: ({}, {}) - {}", latitude, longitude, e.getMessage());
        }

        return null;
    }

    /**
     * 지역 정보를 담는 내부 클래스
     */
    public static class RegionInfo {
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
