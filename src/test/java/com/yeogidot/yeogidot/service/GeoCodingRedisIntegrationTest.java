package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 실행: RUN_REDIS_INTEGRATION_TEST=true, GEOCODING_TEST_REDIS_PORT=6380 설정 후
 * gradlew test --tests '*GeoCodingRedisIntegrationTest' --rerun-tasks
 *
 * 기준 테스트의 12개 시나리오를 실제 RedisCacheManager로 다시 실행한다.
 * 애플리케이션 설정/DB/API 키는 로드하지 않는다. 카카오 HTTP만 Mock이다.
 * 127.0.0.1의 별도 테스트 Redis가 필요하며, 일반 test 실행에서는 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TEST", matches = "true")
@ContextConfiguration(classes = GeoCodingRedisIntegrationTest.RedisConfig.class, inheritLocations = false)
class GeoCodingRedisIntegrationTest extends GeoCodingCacheBaselineTest {
    private static final String PREFIX = "yeogidot-geocoding-test-" + UUID.randomUUID() + "::";
    private static final BigDecimal LATITUDE = new BigDecimal("37.497900");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.027600");

    @Autowired private GeoCodingService service;
    @Autowired private CacheManager cacheManager;
    @Autowired private RedisConnectionFactory connectionFactory;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        String portValue = System.getenv("GEOCODING_TEST_REDIS_PORT");
        if (portValue == null) {
            throw new IllegalStateException("GEOCODING_TEST_REDIS_PORT에 전용 Redis 포트를 지정하세요.");
        }
        int port = Integer.parseInt(portValue);
        if (port < 1024 || port > 65535 || port == 6379) {
            throw new IllegalArgumentException("기본 6379 대신 별도의 테스트 Redis 포트가 필요합니다.");
        }
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> port);
        properties.add("spring.data.redis.database", () -> 0);
        properties.add("spring.data.redis.connect-timeout", () -> "2s");
        properties.add("spring.data.redis.timeout", () -> "2s");
        properties.add("spring.cache.type", () -> "redis");
        properties.add("spring.cache.redis.time-to-live", () -> "86400000");
        properties.add("spring.cache.redis.key-prefix", () -> PREFIX);
        properties.add("spring.cache.redis.use-key-prefix", () -> "true");
    }

    @Test
    void storesSerializedRegionInRedisWith24HourTtl() {
        assertInstanceOf(RedisCacheManager.class, cacheManager);
        GeoCodingService.RegionInfo original = service.getDetailedRegion(LATITUDE, LONGITUDE);
        byte[] key = (PREFIX + "geocoding::detail:37.4979,127.0276").getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            byte[] bytes = connection.stringCommands().get(key);
            assertNotNull(bytes, "실제 Redis에 키가 저장되어 있어야 한다");
            GeoCodingService.RegionInfo restored = assertInstanceOf(GeoCodingService.RegionInfo.class,
                    new JdkSerializationRedisSerializer().deserialize(bytes));
            assertNotSame(original, restored);
            assertEquals("서울특별시", restored.getRegion1depth());
            assertEquals("강남구", restored.getRegion2depth());
            Long ttl = connection.keyCommands().pTtl(key);
            assertNotNull(ttl);
            assertTrue(ttl > 86_340_000 && ttl <= 86_400_000, "24시간 TTL이 적용되어야 한다: " + ttl);
        }
    }

    @Test
    void freshServiceAndCacheManagerReusePreviouslyStoredRedisValue() {
        service.getDetailedRegion(LATITUDE, LONGITUDE);
        // 로컬 inFlight나 기존 CacheManager 객체에 의존하지 않는지 확인한다.
        RedisCacheManager freshManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(org.springframework.data.redis.cache.RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith(PREFIX))
                .build();
        freshManager.afterPropertiesSet();
        RestTemplate freshHttp = mock(RestTemplate.class);
        GeoCodingService freshService = new GeoCodingService(freshHttp, freshManager);
        assertEquals("서울특별시 강남구", freshService.getDistrictFromCoordinates(LATITUDE, LONGITUDE));
        assertEquals("서울특별시", freshService.getRegionFromCoordinates(LATITUDE, LONGITUDE));
        verifyNoInteractions(freshHttp);
    }

    @AfterAll
    static void removeOnlyThisRunsKeys(@Autowired CacheManager manager) {
        // Cache.clear()는 고유 접두사 아래의 geocoding 키만 삭제한다. FLUSHDB/FLUSHALL은 사용하지 않는다.
        Objects.requireNonNull(manager.getCache("geocoding")).clear();
    }

    @Configuration
    @EnableCaching
    @ImportAutoConfiguration({RedisAutoConfiguration.class, CacheAutoConfiguration.class})
    static class RedisConfig {
        @Bean RestTemplate restTemplate() { return mock(RestTemplate.class); }
        @Bean GeoCodingService geoCodingService(RestTemplate http, CacheManager manager) {
            return new GeoCodingService(http, manager);
        }
    }
}
