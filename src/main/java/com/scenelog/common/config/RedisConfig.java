package com.scenelog.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenelog.analytics.dto.TimelineBucketResponse;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

/**
 * Redis 캐시 (기획서 §5-A-4 ⑤).
 *
 * <p><b>무효화 전략이 캐시의 핵심이다</b>: 주 경로는 집계 완료 시의 명시적 evict
 * ({@code AggregationService}의 {@code @CacheEvict}). TTL 10분은 evict가 누락됐을 때의
 * 안전망(벨트 앤 서스펜더)이지 주 무효화 수단이 아니다.
 *
 * <p>Caffeine(로컬 캐시)이 아니라 Redis인 이유: 캐시를 프로세스 밖에 두면
 * 인스턴스가 늘어나도 캐시 일관성이 유지된다 (기획서 §4 설계원칙 4).
 */
@Configuration
@EnableCaching
public class RedisConfig {

    public static final String CACHE_TIMELINE = "timeline";

    /**
     * 직렬화기 선택 (트러블슈팅 5호):
     * GenericJackson2JsonRedisSerializer는 역직렬화에 @class 타입 메타데이터가 필요한데,
     * <b>record는 final 클래스라 기본 정책(NON_FINAL)에서 타입 정보가 기록되지 않는다</b> —
     * 저장은 되고 읽기(캐시 히트)만 500이 나는 함정.
     * 타임라인 캐시는 값 타입을 이미 알고 있으므로, 타입을 명시한 직렬화기를 쓴다.
     * 메타데이터가 없으니 JSON도 더 작고 깨끗하다.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        var timelineType = mapper.getTypeFactory()
                .constructCollectionType(List.class, TimelineBucketResponse.class);

        RedisCacheConfiguration timelineConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .prefixCacheNameWith("scenelog:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, timelineType)));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(CACHE_TIMELINE, timelineConfig)
                .cacheDefaults(timelineConfig)
                .build();
    }
}
