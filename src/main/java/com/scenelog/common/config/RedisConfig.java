package com.scenelog.common.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

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

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .prefixCacheNameWith("scenelog:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
