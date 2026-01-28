package com.yeogidot.yeogidot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class GeoCodingService {

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 위도/경도로 지역명 조회 (시/도 단위)
     * @param latitude 위도
     * @param longitude 경도
     * @return 지역명 (예: "부산광역시", "제주특별자치도")
     */
    public String getRegionFromCoordinates(BigDecimal latitude, BigDecimal longitude) {
        RegionInfo regionInfo = getDetailedRegion(latitude, longitude);
        return regionInfo != null ? regionInfo.getRegion1depth() : null;
    }

    /**
     * 위도/경도로 상세 지역 정보 조회
     * @param latitude 위도
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
                java.util.List<Map<String, Object>> documents = 
                    (java.util.List<Map<String, Object>>) body.get("documents");
                
                if (!documents.isEmpty()) {
                    Map<String, Object> firstDoc = documents.get(0);
                    String region1depth = (String) firstDoc.get("region_1depth_name");
                    String region2depth = (String) firstDoc.get("region_2depth_name");
                    
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
