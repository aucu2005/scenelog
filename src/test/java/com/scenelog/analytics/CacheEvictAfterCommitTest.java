package com.scenelog.analytics;

import com.scenelog.common.config.RedisConfig;
import com.scenelog.content.Content;
import com.scenelog.content.ContentRepository;
import com.scenelog.content.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * 캐시 무효화와 DB 커밋의 순서 (수정계획서 §5-2, 스펙 2026-09-14 §3-1).
 *
 * <p>evict가 커밋보다 먼저 실행되면, 그 사이에 들어온 조회가 아직 커밋되지 않은 옛 집계를 읽어
 * 캐시에 다시 넣고 TTL(10분) 동안 옛 값이 보인다. 이 테스트는 evict가 커밋 <b>뒤</b>에 실행됨을 고정한다.
 *
 * <p>키 존재 여부는 캐시 추상화가 아니라 <b>새 Redis 연결의 EXISTS</b>로 본다 — 실제 서버 상태가 기준이다.
 * Spring Data Redis 4.1의 {@code DefaultRedisCacheWriter}는 put/evict를 <b>비동기</b>로 보내
 * 서버 반영 전에 리턴하므로(실측 2026-09-14), 단언은 Awaitility로 "결국 그렇게 된다/그동안 유지된다"로 쓴다.
 * 실제 Redis·PostgreSQL을 쓴다 (contextLoads와 같은 전제: {@code docker compose up -d}).
 */
@SpringBootTest
class CacheEvictAfterCommitTest {

    private static final Long PROBE_KEY = 987_654_321L;
    private static final Duration EVENTUALLY = Duration.ofSeconds(2);
    private static final Duration HOLDS_FOR = Duration.ofMillis(400);

    @Autowired CacheManager cacheManager;
    @Autowired RedisConnectionFactory connectionFactory;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AggregationService aggregationService;
    @Autowired ContentRepository contentRepository;
    @Autowired SegmentStatRepository segmentStatRepository;
    @Autowired HighlightRepository highlightRepository;

    private Cache cache;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(RedisConfig.CACHE_TIMELINE);
        tx = new TransactionTemplate(txManager);
        cache.evict(PROBE_KEY);
        await().atMost(EVENTUALLY).until(() -> !existsInRedis(PROBE_KEY));
    }

    @AfterEach
    void tearDown() {
        cache.evict(PROBE_KEY);
    }

    /** RedisConfig의 prefix("scenelog:") + 캐시명 + "::" + 키 — 실제 서버에 저장되는 키 */
    private static String redisKey(Long id) {
        return "scenelog:" + RedisConfig.CACHE_TIMELINE + "::" + id;
    }

    private boolean existsInRedis(Long id) {
        try (RedisConnection c = connectionFactory.getConnection()) {
            return Boolean.TRUE.equals(c.keyCommands().exists(redisKey(id).getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void putAndWait(Long id) {
        cache.put(id, List.of());
        await().alias("사전 조건: 캐시에 값이 있다").atMost(EVENTUALLY).until(() -> existsInRedis(id));
    }

    /** 조건이 HOLDS_FOR 동안 계속 참이어야 한다 — "아직 지워지지 않았다"를 비동기 환경에서 말하는 방법 */
    private void assertStillExists(Long id, String why) {
        await().alias(why).during(HOLDS_FOR).atMost(EVENTUALLY).until(() -> existsInRedis(id));
    }

    private void assertEventuallyGone(Long id, String why) {
        await().alias(why).atMost(EVENTUALLY).until(() -> !existsInRedis(id));
    }

    @Test
    void 트랜잭션_안의_evict는_커밋_뒤로_미뤄진다() {
        putAndWait(PROBE_KEY);

        tx.executeWithoutResult(status -> {
            cache.evict(PROBE_KEY);
            assertStillExists(PROBE_KEY, "커밋 전에는 아직 지워지지 않아야 한다 (지워지면 그 사이 조회가 옛 값을 재적재한다)");
        });

        assertEventuallyGone(PROBE_KEY, "커밋 직후에는 지워져야 한다");
    }

    @Test
    void 롤백되면_evict는_실행되지_않는다() {
        putAndWait(PROBE_KEY);

        tx.executeWithoutResult(status -> {
            cache.evict(PROBE_KEY);
            status.setRollbackOnly();
        });

        assertStillExists(PROBE_KEY, "DB가 바뀌지 않았으므로 캐시도 그대로 유효해야 한다");
    }

    @Test
    void 집계의_CacheEvict도_바깥_트랜잭션_커밋까지_기다린다() {
        Content content = contentRepository.save(Content.builder()
                .tmdbId(-914_001).title("cache-order-probe").contentType(ContentType.MOVIE)
                .durationSec(600).build());
        Long id = content.getContentId();
        try {
            putAndWait(id);

            tx.executeWithoutResult(status -> {
                aggregationService.aggregate(id);   // @Transactional(REQUIRED) → 바깥 트랜잭션에 참여
                assertStillExists(id, "집계 메서드가 끝났어도 커밋 전이면 캐시는 남아 있어야 한다");
            });

            assertEventuallyGone(id, "커밋 뒤에는 지워져야 한다");
        } finally {
            tx.executeWithoutResult(s -> {
                highlightRepository.deleteAllByContentIdAndMethod(id, Highlight.METHOD_ZSCORE_V2);
                segmentStatRepository.deleteAllByContentId(id);
                contentRepository.deleteById(id);
            });
            cache.evict(id);
        }
    }
}
