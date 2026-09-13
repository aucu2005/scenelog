# 배치 운영 보강 + README 동기화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수정계획서 §5를 실행한다 — ② 캐시 evict를 커밋 이후로 지연(+순서 테스트), ① batch 프로파일 `@Scheduled` 정기 집계, ③ 집계 실행 이력 `batch_runs`(+대시보드·장애 주입), 그리고 README 9항목을 서류·코드와 같은 숫자로 맞춘다.

**Architecture:** `RedisCacheManager`를 `transactionAware()`로 만들어 evict가 afterCommit에 실행되게 한다. `analytics.batch` 패키지에 프로파일 게이트 스케줄러와 실행 이력(엔티티·REQUIRES_NEW 기록기·잡 러너)을 두고, 관리자 API와 스케줄러가 같은 러너를 통과하게 한다. README는 마지막에 최종 테스트 수를 넣어 갱신하고 태그로 심사 시점을 고정한다.

**Tech Stack:** Spring Boot 4.1.0(Java 17), Spring Data Redis 4.1(`RedisCacheManagerBuilder.transactionAware`), Spring Scheduling, JPA/PostgreSQL 16, MongoDB 7(`MongoTemplate.findDistinct`), JUnit 5·AssertJ·Mockito 5(starter-test 포함), Docker Compose(로컬 DB 3종).

**스펙:** `docs/superpowers/specs/2026-09-14-batch-ops-readme-sync-design.md`

## Global Constraints

- 브랜치 `feature/batch-ops-readme-0914`에서 작업. 완료 후 main에 fast-forward 병합, 태그 `submission-2026-09`. **push는 사용자 결정.**
- 모든 명령은 `C:\Users\aucu2\Project\movieprojcet\scenelog`(Git Bash `/c/Users/aucu2/Project/movieprojcet/scenelog`)에서.
- 로컬 Docker DB 3종(`docker compose up -d`)이 떠 있어야 `@SpringBootTest` 계열이 돈다. 2026-09-14 기준 기동·healthy 확인됨.
- 커밋 메시지 한국어, 푸터 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- 기존 검출 상수(`Z_THRESHOLD`·`DEFAULT_MIN_LIFT`)·API 응답 JSON 모양 무변경.
- README의 숫자는 **실측값만**: 테스트 수는 마지막 `./gradlew test` 결과, 검출 수치는 dev-log 8/9 표.
- 안 한 것(ShedLock, 경쟁 창, 데모 중지)은 한 것처럼 쓰지 않는다.

---

### Task 1: ② 캐시 evict를 커밋 이후로 — `transactionAware()` + 순서 테스트 3건

**Files:**
- Modify: `src/main/java/com/scenelog/common/config/RedisConfig.java`
- Test: `src/test/java/com/scenelog/analytics/CacheEvictAfterCommitTest.java` (신규)

**Interfaces:**
- Produces: 없음(설정 변경). `RedisConfig.CACHE_TIMELINE` 상수는 그대로.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 무효화와 DB 커밋의 순서 (수정계획서 §5-2, 스펙 §3-1).
 * evict가 커밋보다 먼저 실행되면, 그 사이 조회가 옛 집계를 캐시에 다시 넣어 TTL 동안 옛 값이 보인다.
 * 실제 Redis·PostgreSQL을 쓴다 (contextLoads와 같은 전제: docker compose up -d).
 */
@SpringBootTest
class CacheEvictAfterCommitTest {

    private static final Long PROBE_KEY = 987_654_321L;

    @Autowired CacheManager cacheManager;
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
    }

    @AfterEach
    void tearDown() {
        cache.evict(PROBE_KEY);
    }

    @Test
    void 트랜잭션_안의_evict는_커밋_뒤로_미뤄진다() {
        cache.put(PROBE_KEY, List.of());

        tx.executeWithoutResult(status -> {
            cache.evict(PROBE_KEY);
            assertThat(cache.get(PROBE_KEY))
                    .as("커밋 전에는 아직 지워지지 않아야 한다 (지워지면 그 사이 조회가 옛 값을 재적재한다)")
                    .isNotNull();
        });

        assertThat(cache.get(PROBE_KEY)).as("커밋 직후에는 지워져 있어야 한다").isNull();
    }

    @Test
    void 롤백되면_evict는_실행되지_않는다() {
        cache.put(PROBE_KEY, List.of());

        tx.executeWithoutResult(status -> {
            cache.evict(PROBE_KEY);
            status.setRollbackOnly();
        });

        assertThat(cache.get(PROBE_KEY)).as("DB가 바뀌지 않았으므로 캐시도 그대로 유효하다").isNotNull();
    }

    @Test
    void 집계의_CacheEvict도_바깥_트랜잭션_커밋까지_기다린다() {
        Content content = contentRepository.save(Content.builder()
                .tmdbId(-914_001).title("cache-order-probe").contentType(ContentType.MOVIE)
                .durationSec(600).build());
        Long id = content.getContentId();
        try {
            cache.put(id, List.of());

            tx.executeWithoutResult(status -> {
                aggregationService.aggregate(id);   // @Transactional(REQUIRED) → 바깥 트랜잭션에 참여
                assertThat(cache.get(id))
                        .as("집계 메서드가 끝났어도 커밋 전이면 캐시는 남아 있어야 한다")
                        .isNotNull();
            });

            assertThat(cache.get(id)).as("커밋 뒤에는 지워져야 한다").isNull();
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.CacheEvictAfterCommitTest" --console=plain`
Expected: 3 tests FAILED — 첫 번째는 "커밋 전에는 아직 지워지지 않아야 한다" 단언에서(evict 즉시 실행), 두 번째는 롤백 후 null, 세 번째는 집계 직후 null.

- [ ] **Step 3: 최소 구현 — 빌더에 `transactionAware()`**

`RedisConfig.cacheManager()`의 반환문을:

```java
        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(CACHE_TIMELINE, timelineConfig)
                .cacheDefaults(timelineConfig)
                // evict/put을 트랜잭션 afterCommit으로 미룬다 — 커밋 전 삭제로 옛 값이 재적재되는 창을 없앤다.
                // 트랜잭션이 없으면 즉시 실행, 롤백이면 실행하지 않는다 (TransactionAwareCacheDecorator).
                // 남는 한계: 커밋 직전에 시작한 조회가 삭제 이후에 옛 값을 쓰는 경쟁 — TTL 10분이 안전망.
                .transactionAware()
                .build();
```

클래스 Javadoc에 한 문단 추가:

```java
 * <p><b>evict와 커밋의 순서</b>(2026-09-14): {@code AggregationService.aggregate()}에는
 * {@code @Transactional}과 {@code @CacheEvict}가 함께 있다. 어느 쪽이 먼저 끝나는지는 AOP 프록시
 * 순서에 달려 있어 문서화된 보장이 아니다. {@code transactionAware()}로 캐시 작업을 커밋 이후로
 * 고정했다 — 검증은 {@code CacheEvictAfterCommitTest}.
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.CacheEvictAfterCommitTest" --console=plain`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/scenelog/common/config/RedisConfig.java src/test/java/com/scenelog/analytics/CacheEvictAfterCommitTest.java
git commit -m "fix(cache): 타임라인 캐시 evict를 트랜잭션 커밋 이후로 지연(transactionAware) + 순서 테스트 3건"
```

---

### Task 2: ① batch 프로파일 `@Scheduled` 정기 집계

**Files:**
- Create: `src/main/java/com/scenelog/analytics/batch/BatchSchedulingConfig.java`
- Create: `src/main/java/com/scenelog/analytics/batch/AggregationScheduler.java`
- Test: `src/test/java/com/scenelog/analytics/batch/AggregationSchedulerTest.java` (Mockito, 컨텍스트 없음)
- Test: `src/test/java/com/scenelog/analytics/batch/BatchProfileWiringTest.java` (`@ActiveProfiles("batch")`)
- Modify: `src/test/java/com/scenelog/ScenelogApplicationTests.java` (기본 프로파일에 스케줄러 없음 단언 1개 추가)

**Interfaces:**
- Consumes: `AggregationService.aggregate(Long)` (Task 3에서 `AggregationJobRunner.run(Long, BatchTrigger)`로 교체됨).
- Produces: `AggregationScheduler.runOnce(): RunSummary`, `record RunSummary(int targets, int succeeded, int failed, long elapsedMs)`.

- [ ] **Step 1: 단위 테스트(실패) 작성**

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationService;
import com.scenelog.reaction.ReactionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AggregationSchedulerTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock AggregationService aggregationService;
    @InjectMocks AggregationScheduler scheduler;

    private void targets(Long... ids) {
        when(mongoTemplate.findDistinct(any(Query.class), eq("contentId"), eq(ReactionEvent.class), eq(Long.class)))
                .thenReturn(List.of(ids));
    }

    @Test
    void 이벤트가_있는_모든_콘텐츠를_집계한다() {
        targets(1L, 2L);

        AggregationScheduler.RunSummary s = scheduler.runOnce();

        verify(aggregationService).aggregate(1L);
        verify(aggregationService).aggregate(2L);
        assertThat(s.targets()).isEqualTo(2);
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.failed()).isZero();
    }

    @Test
    void 한_콘텐츠의_실패가_나머지_집계를_막지_않는다() {
        targets(1L, 2L, 3L);
        when(aggregationService.aggregate(2L)).thenThrow(new RuntimeException("mongo down"));

        AggregationScheduler.RunSummary s = scheduler.runOnce();

        verify(aggregationService).aggregate(3L);   // 2번이 실패해도 3번은 돈다
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.batch.AggregationSchedulerTest" --console=plain`
Expected: compileTestJava FAILED — `AggregationScheduler` 심볼 없음.

- [ ] **Step 3: 구현**

`BatchSchedulingConfig.java`:

```java
package com.scenelog.analytics.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러는 batch 프로파일에서만 켠다 (수정계획서 §5-3).
 * 기본 프로파일(데모·RAM 1GB 서버)은 관리자 API 수동 트리거만 남는다 — 120만 건 재집계를
 * 주기적으로 돌리는 위험을 데모 서버에 주지 않기 위해서다.
 */
@Configuration
@EnableScheduling
@Profile("batch")
public class BatchSchedulingConfig {
}
```

`AggregationScheduler.java`:

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationService;
import com.scenelog.reaction.ReactionEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 정기 집계 (batch 프로파일 전용).
 *
 * <p>fixedDelay = 이전 실행이 <b>끝난 시점</b>부터 대기. 120만 건 재집계가 주기보다 오래 걸려도
 * 겹쳐 돌지 않는다(fixedRate라면 겹친다). 대상은 reaction_events의 distinct contentId —
 * (contentId, offsetSec) 복합 인덱스의 접두 컬럼이라 인덱스만 읽는다.
 *
 * <p>콘텐츠 하나의 실패가 나머지를 막지 않도록 건별로 격리한다. 다중 인스턴스 동시 실행 방지
 * (ShedLock 등)는 미적용 — 단일 인스턴스 전제이며 인스턴스가 늘 때 필요한 항목으로 기록한다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class AggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AggregationScheduler.class);

    private final MongoTemplate mongoTemplate;
    private final AggregationService aggregationService;

    public record RunSummary(int targets, int succeeded, int failed, long elapsedMs) {}

    @Scheduled(fixedDelayString = "${scenelog.batch.aggregate.fixed-delay:PT10M}",
               initialDelayString = "${scenelog.batch.aggregate.initial-delay:PT30S}")
    public void scheduled() {
        runOnce();
    }

    public RunSummary runOnce() {
        long t0 = System.currentTimeMillis();
        List<Long> targets = mongoTemplate.findDistinct(new Query(), "contentId", ReactionEvent.class, Long.class);
        log.info("정기 집계 시작 — 대상 {}편", targets.size());

        int succeeded = 0;
        int failed = 0;
        for (Long contentId : targets) {
            try {
                aggregationService.aggregate(contentId);
                succeeded++;
            } catch (RuntimeException e) {
                failed++;
                log.error("정기 집계 실패 — contentId={}: {}", contentId, e.toString());
            }
        }

        RunSummary summary = new RunSummary(targets.size(), succeeded, failed, System.currentTimeMillis() - t0);
        log.info("정기 집계 완료 — 대상 {}편, 성공 {}, 실패 {}, {}ms",
                summary.targets(), summary.succeeded(), summary.failed(), summary.elapsedMs());
        return summary;
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.batch.AggregationSchedulerTest" --console=plain`
Expected: 2 passed.

- [ ] **Step 5: 프로파일 배선 테스트 작성·실행**

`BatchProfileWiringTest.java`:

```java
package com.scenelog.analytics.batch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** batch 프로파일에서만 스케줄러가 살아난다. initial-delay를 1시간으로 줘 테스트 중 실제 집계는 돌지 않는다. */
@SpringBootTest(properties = "scenelog.batch.aggregate.initial-delay=PT1H")
@ActiveProfiles("batch")
class BatchProfileWiringTest {

    @Autowired ApplicationContext ctx;

    @Test
    void batch_프로파일에서는_정기_집계_태스크가_1개_등록된다() {
        assertThat(ctx.getBeanNamesForType(AggregationScheduler.class)).hasSize(1);
        var registrar = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
        assertThat(registrar.getScheduledTasks()).hasSize(1);
    }
}
```

`ScenelogApplicationTests.java`에 추가:

```java
	@Autowired ApplicationContext ctx;

	@Test
	void 기본_프로파일에는_정기_집계_스케줄러가_없다() {
		assertThat(ctx.getBeanNamesForType(AggregationScheduler.class)).isEmpty();
	}
```
(import: `org.springframework.beans.factory.annotation.Autowired`, `org.springframework.context.ApplicationContext`, `com.scenelog.analytics.batch.AggregationScheduler`, `static org.assertj.core.api.Assertions.assertThat`)

Run: `./gradlew test --tests "com.scenelog.analytics.batch.*" --tests "com.scenelog.ScenelogApplicationTests" --console=plain`
Expected: 모두 통과.

- [ ] **Step 6: 로컬 batch 프로파일 실행 로그 캡처 (Task 3 이후, 러너 교체 뒤 한 번만)**

```bash
./gradlew bootRun --args='--spring.profiles.active=batch --scenelog.batch.aggregate.fixed-delay=PT20S --scenelog.batch.aggregate.initial-delay=PT5S' > /c/Users/aucu2/AppData/Local/Temp/claude/batch-run.log 2>&1 &
# "정기 집계 완료" 2줄 이상 확인 후 종료
grep "정기 집계" /c/Users/aucu2/AppData/Local/Temp/claude/batch-run.log
```
Expected: `정기 집계 시작 — 대상 51편` / `정기 집계 완료 — 대상 51편, 성공 51, 실패 0, NNNNms` 가 2회 이상. 발췌를 dev-log에 붙인다(Task 5).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/scenelog/analytics/batch src/test/java/com/scenelog/analytics/batch src/test/java/com/scenelog/ScenelogApplicationTests.java
git commit -m "feat(batch): batch 프로파일에서 @Scheduled 정기 집계 — 콘텐츠별 격리 실행, 기본 프로파일은 수동 트리거 유지"
```

---

### Task 3: ③ 집계 실행 이력 `batch_runs` — 기록기·러너·조회 API·대시보드 카드

**Files:**
- Create: `src/main/java/com/scenelog/analytics/batch/BatchTrigger.java`, `BatchRunStatus.java`, `BatchRun.java`, `BatchRunRepository.java`, `BatchRunRecorder.java`, `AggregationJobRunner.java`, `BatchRunController.java`, `dto/BatchRunResponse.java`
- Create: `src/main/java/com/scenelog/analytics/AggregationResult.java`
- Modify: `src/main/java/com/scenelog/analytics/AggregationService.java` (반환 타입 `AggregationResult`)
- Modify: `src/main/java/com/scenelog/analytics/AnalyticsAdminController.java` (러너 경유)
- Modify: `src/main/java/com/scenelog/analytics/batch/AggregationScheduler.java` (러너 경유)
- Modify: `src/main/java/com/scenelog/common/config/SecurityConfig.java` (`GET /api/batch-runs` permitAll)
- Modify: `src/main/resources/static/index.html` (카드 + JS + 검출 타일 v2 숫자)
- Test: `src/test/java/com/scenelog/analytics/batch/AggregationJobRunnerTest.java` (Mockito), `BatchRunRecorderTest.java` (`@SpringBootTest`), `AggregationSchedulerTest.java` 수정(러너 목)

**Interfaces:**
- Produces: `record AggregationResult(Long contentId, int events, int buckets, List<HighlightWindow> highlights)` + `Map<String,Object> toResponse()`; `AggregationJobRunner.run(Long contentId, BatchTrigger trigger): AggregationResult`; `BatchRunRecorder.start(String, Long, BatchTrigger): Long`, `succeed(Long runId, AggregationResult)`, `fail(Long runId, Throwable)`; `BatchRunRepository.findAllByOrderByStartedAtDesc(Pageable): List<BatchRun>`.

- [ ] **Step 1: 러너 단위 테스트(실패) 작성**

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import com.scenelog.analytics.AggregationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AggregationJobRunnerTest {

    @Mock AggregationService aggregationService;
    @Mock BatchRunRecorder recorder;
    @InjectMocks AggregationJobRunner runner;

    @Test
    void 성공하면_시작과_성공을_기록하고_결과를_돌려준다() {
        AggregationResult result = new AggregationResult(7L, 120, 12, List.of());
        when(recorder.start(AggregationJobRunner.JOB_AGGREGATE, 7L, BatchTrigger.MANUAL)).thenReturn(42L);
        when(aggregationService.aggregate(7L)).thenReturn(result);

        AggregationResult returned = runner.run(7L, BatchTrigger.MANUAL);

        assertThat(returned).isSameAs(result);
        verify(recorder).succeed(42L, result);
        verify(recorder, never()).fail(any(), any());
    }

    @Test
    void 실패하면_실패를_기록하고_예외는_그대로_전파한다() {
        RuntimeException boom = new IllegalStateException("mongo down");
        when(recorder.start(AggregationJobRunner.JOB_AGGREGATE, 7L, BatchTrigger.SCHEDULED)).thenReturn(43L);
        when(aggregationService.aggregate(7L)).thenThrow(boom);

        assertThatThrownBy(() -> runner.run(7L, BatchTrigger.SCHEDULED)).isSameAs(boom);

        verify(recorder).fail(eq(43L), eq(boom));
        verify(recorder, never()).succeed(any(), any());
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.batch.AggregationJobRunnerTest" --console=plain`
Expected: compileTestJava FAILED (`AggregationResult`, `AggregationJobRunner`, `BatchRunRecorder`, `BatchTrigger` 없음).

- [ ] **Step 3: 구현**

`AggregationResult.java` (analytics 패키지):

```java
package com.scenelog.analytics;

import java.util.List;
import java.util.Map;

/** 집계 1회의 결과 — 실행 이력(batch_runs)이 카운트를 타입으로 읽기 위해 Map 대신 레코드로 (2026-09-14) */
public record AggregationResult(Long contentId, int events, int buckets, List<HighlightWindow> highlights) {

    /** 관리자 API 응답 JSON — 이전 Map 응답과 같은 모양 */
    public Map<String, Object> toResponse() {
        return Map.of(
                "contentId", contentId,
                "events", events,
                "buckets", buckets,
                "highlights", highlights.stream()
                        .map(w -> Map.of("startSec", w.startSec(), "endSec", w.endSec(),
                                "score", Math.round(w.score() * 1000) / 1000.0))
                        .toList());
    }
}
```

`AggregationService.aggregate()` — 시그니처와 반환문만 교체:

```java
    public AggregationResult aggregate(Long contentId) {
        ...(본문 동일)...
        return new AggregationResult(contentId, events.size(), buckets.size(), windows);
    }
```
(`import java.util.Map;` 사용처가 남는지 확인 — `Map<Integer, BucketCounts>`에 여전히 쓰인다.)

`BatchTrigger.java`:

```java
package com.scenelog.analytics.batch;

/** 누가 집계를 시작했나 — 관리자 API(MANUAL) / batch 프로파일 스케줄러(SCHEDULED) */
public enum BatchTrigger { MANUAL, SCHEDULED }
```

`BatchRunStatus.java`:

```java
package com.scenelog.analytics.batch;

public enum BatchRunStatus { RUNNING, SUCCESS, FAILED }
```

`BatchRun.java`:

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 배치 실행 1회 = 1행 (수정계획서 §5-4). "배치가 실패하면 어떻게 아세요?"에 대한 답이 이 표다.
 * 실패 행도 남아야 하므로 기록은 집계 트랜잭션과 분리된 트랜잭션에서 쓴다 ({@link BatchRunRecorder}).
 */
@Entity
@Table(name = "batch_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchRun {

    static final int ERROR_MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long runId;

    @Column(nullable = false, length = 40)
    private String jobName;

    private Long contentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchTrigger triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchRunStatus status;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;
    private Long durationMs;

    private Integer eventCount;
    private Integer bucketCount;
    private Integer highlightCount;

    /** 예외 클래스 단순명 — 공개 API에는 여기까지만 노출한다 */
    @Column(length = 120)
    private String errorType;

    /** 최상위 원인 메시지 500자 — 관리자 API에만 노출 (내부 호스트명 등이 섞일 수 있다) */
    @Column(length = ERROR_MESSAGE_MAX)
    private String errorMessage;

    public static BatchRun start(String jobName, Long contentId, BatchTrigger trigger) {
        BatchRun r = new BatchRun();
        r.jobName = jobName;
        r.contentId = contentId;
        r.triggeredBy = trigger;
        r.status = BatchRunStatus.RUNNING;
        r.startedAt = OffsetDateTime.now();
        return r;
    }

    public void succeed(AggregationResult result) {
        finish();
        this.status = BatchRunStatus.SUCCESS;
        this.eventCount = result.events();
        this.bucketCount = result.buckets();
        this.highlightCount = result.highlights().size();
    }

    public void fail(Throwable t) {
        finish();
        this.status = BatchRunStatus.FAILED;
        this.errorType = t.getClass().getSimpleName();
        String msg = String.valueOf(rootCause(t).getMessage());
        this.errorMessage = msg.length() > ERROR_MESSAGE_MAX ? msg.substring(0, ERROR_MESSAGE_MAX) : msg;
    }

    private void finish() {
        this.finishedAt = OffsetDateTime.now();
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }
}
```

`BatchRunRepository.java`:

```java
package com.scenelog.analytics.batch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatchRunRepository extends JpaRepository<BatchRun, Long> {
    List<BatchRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
```

`BatchRunRecorder.java`:

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 이력 기록기. 세 메서드 모두 {@code REQUIRES_NEW} —
 * <b>실패 기록이 집계 트랜잭션과 운명을 같이하면, 집계가 실패했을 때 그 기록도 함께 롤백된다.</b>
 * 별도 트랜잭션으로 끊어 두면 집계가 어떻게 끝나든 행이 남는다 (검증: BatchRunRecorderTest).
 */
@Component
@RequiredArgsConstructor
public class BatchRunRecorder {

    private final BatchRunRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String jobName, Long contentId, BatchTrigger trigger) {
        return repository.save(BatchRun.start(jobName, contentId, trigger)).getRunId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long runId, AggregationResult result) {
        repository.findById(runId).ifPresent(r -> r.succeed(result));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long runId, Throwable t) {
        repository.findById(runId).ifPresent(r -> r.fail(t));
    }
}
```

`AggregationJobRunner.java`:

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import com.scenelog.analytics.AggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 집계를 "실행 이력이 남는 잡"으로 감싼다. 관리자 API(MANUAL)와 스케줄러(SCHEDULED)가 모두 여기를 지난다.
 * 예외는 삼키지 않는다 — 기록만 하고 그대로 전파해 호출자(HTTP 500, 스케줄러의 건별 격리)가 결정한다.
 */
@Component
@RequiredArgsConstructor
public class AggregationJobRunner {

    public static final String JOB_AGGREGATE = "AGGREGATE";

    private final AggregationService aggregationService;
    private final BatchRunRecorder recorder;

    public AggregationResult run(Long contentId, BatchTrigger trigger) {
        Long runId = recorder.start(JOB_AGGREGATE, contentId, trigger);
        try {
            AggregationResult result = aggregationService.aggregate(contentId);
            recorder.succeed(runId, result);
            return result;
        } catch (RuntimeException e) {
            recorder.fail(runId, e);
            throw e;
        }
    }
}
```

`dto/BatchRunResponse.java`:

```java
package com.scenelog.analytics.batch.dto;

import com.scenelog.analytics.batch.BatchRun;
import com.scenelog.analytics.batch.BatchRunStatus;
import com.scenelog.analytics.batch.BatchTrigger;

import java.time.OffsetDateTime;

public record BatchRunResponse(
        Long runId, String jobName, Long contentId, BatchTrigger triggeredBy, BatchRunStatus status,
        OffsetDateTime startedAt, OffsetDateTime finishedAt, Long durationMs,
        Integer eventCount, Integer bucketCount, Integer highlightCount,
        String errorType, String errorMessage) {

    /** 공개 뷰 — 오류는 예외 타입까지만 */
    public static BatchRunResponse publicView(BatchRun r) {
        return of(r, null);
    }

    /** 관리자 뷰 — 오류 메시지 포함 */
    public static BatchRunResponse adminView(BatchRun r) {
        return of(r, r.getErrorMessage());
    }

    private static BatchRunResponse of(BatchRun r, String errorMessage) {
        return new BatchRunResponse(r.getRunId(), r.getJobName(), r.getContentId(), r.getTriggeredBy(),
                r.getStatus(), r.getStartedAt(), r.getFinishedAt(), r.getDurationMs(),
                r.getEventCount(), r.getBucketCount(), r.getHighlightCount(), r.getErrorType(), errorMessage);
    }
}
```

`BatchRunController.java`:

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.batch.dto.BatchRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 배치 실행 이력 조회 — 공개(대시보드)는 오류 타입까지, 관리자는 메시지까지 */
@RestController
@RequiredArgsConstructor
@Tag(name = "배치 실행 이력", description = "집계 실행 1회 = 1행. 실패 행도 남는다")
public class BatchRunController {

    private static final int MAX_LIMIT = 100;

    private final BatchRunRepository repository;

    @GetMapping("/api/batch-runs")
    @Operation(summary = "최근 배치 실행 (공개)", description = "대시보드용. 오류는 예외 타입까지만 노출")
    public List<BatchRunResponse> recent(@RequestParam(defaultValue = "10") int limit) {
        return repository.findAllByOrderByStartedAtDesc(page(limit)).stream()
                .map(BatchRunResponse::publicView).toList();
    }

    @GetMapping("/api/admin/batch-runs")
    @Operation(summary = "최근 배치 실행 (관리자)", description = "오류 메시지(최상위 원인, 500자) 포함")
    public List<BatchRunResponse> recentDetailed(@RequestParam(defaultValue = "10") int limit) {
        return repository.findAllByOrderByStartedAtDesc(page(limit)).stream()
                .map(BatchRunResponse::adminView).toList();
    }

    private static PageRequest page(int limit) {
        return PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }
}
```

`AnalyticsAdminController` — 의존성을 `AggregationJobRunner jobRunner`로 바꾸고:

```java
    public Map<String, Object> aggregate(@PathVariable Long contentId) {
        return jobRunner.run(contentId, BatchTrigger.MANUAL).toResponse();
    }
```

`AggregationScheduler` — `AggregationService` 대신 `AggregationJobRunner jobRunner`를 주입하고 루프 안을 `jobRunner.run(contentId, BatchTrigger.SCHEDULED);`로. `AggregationSchedulerTest`의 `@Mock AggregationService`를 `@Mock AggregationJobRunner jobRunner`로, `verify(aggregationService).aggregate(1L)` → `verify(jobRunner).run(1L, BatchTrigger.SCHEDULED)`, `when(aggregationService.aggregate(2L)).thenThrow(...)` → `when(jobRunner.run(2L, BatchTrigger.SCHEDULED)).thenThrow(...)`.

`SecurityConfig` — GET 공개 매처에 추가:

```java
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/contents/**",
                                "/api/batch-runs"          // 배치 실행 이력 공개 뷰 (대시보드 — 오류 타입까지만) 2026-09-14
                        ).permitAll()
```

- [ ] **Step 4: 러너·스케줄러 단위 테스트 통과 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.batch.AggregationJobRunnerTest" --tests "com.scenelog.analytics.batch.AggregationSchedulerTest" --console=plain`
Expected: 4 passed.

- [ ] **Step 5: 기록기 통합 테스트 작성·실행**

```java
package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실행 이력은 집계 트랜잭션과 운명을 같이하지 않는다 (REQUIRES_NEW). 실제 PostgreSQL 사용. */
@SpringBootTest
class BatchRunRecorderTest {

    @Autowired BatchRunRecorder recorder;
    @Autowired BatchRunRepository repository;
    @Autowired PlatformTransactionManager txManager;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        repository.deleteAllById(created);
    }

    @Test
    void 실패_기록은_바깥_트랜잭션이_롤백돼도_남는다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long[] runId = new Long[1];

        tx.executeWithoutResult(status -> {
            runId[0] = recorder.start(AggregationJobRunner.JOB_AGGREGATE, 1L, BatchTrigger.MANUAL);
            recorder.fail(runId[0], new IllegalStateException("주입한 실패"));
            status.setRollbackOnly();   // 집계 트랜잭션이 롤백되는 상황
        });
        created.add(runId[0]);

        BatchRun saved = repository.findById(runId[0]).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(saved.getErrorType()).isEqualTo("IllegalStateException");
        assertThat(saved.getErrorMessage()).contains("주입한 실패");
        assertThat(saved.getFinishedAt()).isNotNull();
    }

    @Test
    void 성공_기록에는_처리_건수와_소요시간이_남는다() {
        Long runId = recorder.start(AggregationJobRunner.JOB_AGGREGATE, 2L, BatchTrigger.SCHEDULED);
        created.add(runId);

        recorder.succeed(runId, new AggregationResult(2L, 300, 30, List.of()));

        BatchRun saved = repository.findById(runId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BatchRunStatus.SUCCESS);
        assertThat(saved.getTriggeredBy()).isEqualTo(BatchTrigger.SCHEDULED);
        assertThat(saved.getEventCount()).isEqualTo(300);
        assertThat(saved.getBucketCount()).isEqualTo(30);
        assertThat(saved.getHighlightCount()).isZero();
        assertThat(saved.getDurationMs()).isNotNull();
    }
}
```

Run: `./gradlew test --tests "com.scenelog.analytics.batch.BatchRunRecorderTest" --console=plain`
Expected: 2 passed (ddl-auto=update가 `batch_runs` 테이블을 만든다).

- [ ] **Step 6: 대시보드 카드 + 검출 타일 v2 숫자**

`index.html` — (a) 검출 타일:

```html
    <div class="card stat"><div class="lb">하이라이트 검출 (v2)</div><div class="vl">정밀도 98.5% · 재현율 97.6%</div><div class="nt">골든셋 120회(12구조×10시드, 검증 시드) · v1 정밀도 31.6% → v2</div></div>
```

(b) 하이라이트 카드 다음에 카드 추가:

```html
  <div class="card gap" style="margin-top:24px">
    <div class="cardTitle"><h2>최근 배치 실행</h2><span class="sub">집계 1회 = batch_runs 1행 · 실패 행도 남는다</span></div>
    <table><thead><tr><th>시작</th><th>대상</th><th>트리거</th><th>상태</th><th>이벤트 → 버킷 → 하이라이트</th><th>소요</th><th>오류</th></tr></thead>
    <tbody id="runBody"><tr><td colspan="7" style="color:var(--t2)">불러오는 중…</td></tr></tbody></table>
  </div>
```

(c) CSS에 상태 배지:

```css
  .st{display:inline-block;font-size:12px;border-radius:999px;padding:3px 10px;font-weight:600}
  .st.ok{background:var(--green-bg);color:var(--green-fg)}
  .st.bad{background:#FEE2E2;color:#991B1B}
  .st.run{background:#F4F4F5;color:var(--t2)}
```

(d) JS — `render()` 아래에 추가하고 즉시실행 함수 첫 줄에서 `loadRuns();` 호출(콘텐츠 유무와 무관하게):

```js
const stCls={SUCCESS:"ok",FAILED:"bad",RUNNING:"run"};
const fmtTime=iso=>{const d=new Date(iso);return `${String(d.getMonth()+1).padStart(2,"0")}-${String(d.getDate()).padStart(2,"0")} ${String(d.getHours()).padStart(2,"0")}:${String(d.getMinutes()).padStart(2,"0")}:${String(d.getSeconds()).padStart(2,"0")}`;};
async function loadRuns(){
  const body=$("runBody");
  try{
    const runs=await j("/api/batch-runs?limit=10");
    body.innerHTML=runs.map(r=>`<tr><td>${fmtTime(r.startedAt)}</td><td>contentId ${r.contentId??"-"}</td><td>${r.triggeredBy}</td>
      <td><span class="st ${stCls[r.status]||"run"}">${r.status}</span></td>
      <td>${r.eventCount??"-"} → ${r.bucketCount??"-"} → ${r.highlightCount??"-"}</td>
      <td>${r.durationMs!=null?r.durationMs+"ms":"-"}</td><td>${r.errorType?`<code>${r.errorType}</code>`:"-"}</td></tr>`).join("")
      ||'<tr><td colspan="7" style="color:var(--t2)">아직 실행 이력이 없습니다 — Swagger에서 집계를 실행해 보세요</td></tr>';
  }catch(e){body.innerHTML='<tr><td colspan="7" style="color:var(--t2)">실행 이력을 불러오지 못했습니다</td></tr>';}
}
```

- [ ] **Step 7: 전체 테스트 + 수동 검증(앱 기동 → 집계 → 이력 조회)**

Run: `./gradlew test --console=plain` → Expected: BUILD SUCCESSFUL, 실패 0.

```bash
./gradlew bootRun > /c/Users/aucu2/AppData/Local/Temp/claude/app.log 2>&1 &
# 기동 후
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"tester@scenelog.dev","password":"password123!"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -s -X POST localhost:8080/api/admin/contents/1/aggregate -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/batch-runs?limit=3
```
Expected: 집계 응답 JSON(이전과 같은 모양), 이력에 `"status":"SUCCESS","triggeredBy":"MANUAL"` 행.

- [ ] **Step 8: 장애 주입 — MongoDB 중지 → 실패 행 → 재기동 → 성공 행**

```bash
docker stop scenelog-mongo
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/admin/contents/1/aggregate -H "Authorization: Bearer $TOKEN"   # ~30초 뒤 500
curl -s "localhost:8080/api/admin/batch-runs?limit=1" -H "Authorization: Bearer $TOKEN"                                             # FAILED + errorType/errorMessage
docker start scenelog-mongo
curl -s -X POST localhost:8080/api/admin/contents/1/aggregate -H "Authorization: Bearer $TOKEN"                                     # 200
curl -s "localhost:8080/api/batch-runs?limit=3"                                                                                     # FAILED 다음 SUCCESS
```
Expected: FAILED 행(`errorType` = Mongo 접속 예외의 단순명, 공개 뷰에는 `errorMessage: null`), 재실행 SUCCESS 행. 브라우저 `http://localhost:8080/`에서 "최근 배치 실행" 카드 캡처 → `docs/images/dashboard-batch-runs.png`. 응답 JSON은 dev-log에.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/scenelog/analytics src/main/java/com/scenelog/common/config/SecurityConfig.java src/main/resources/static/index.html src/test/java/com/scenelog/analytics/batch docs/images/dashboard-batch-runs.png
git commit -m "feat(batch): 집계 실행 이력 batch_runs — 실패 행 보존(REQUIRES_NEW), 공개/관리자 조회 API, 대시보드 카드"
```

---

### Task 4: batch 프로파일 실제 실행 로그 캡처 (Task 2 Step 6)

- [ ] Task 2 Step 6의 명령으로 20초 간격 2회 이상 실행 확인 → 로그 발췌 저장. `docker exec scenelog-postgres psql -U scenelog -d scenelog -c "select run_id, content_id, triggered_by, status, event_count, duration_ms from batch_runs where triggered_by='SCHEDULED' order by run_id desc limit 5"`로 SCHEDULED 행이 쌓였는지 확인.

---

### Task 5: dev-log 9/14 항목 + HANDOFF 갱신

**Files:**
- Modify: `docs/dev-log.md` (끝에 `## 2026-09-14` 항목)
- Modify: `docs/HANDOFF.md` (gitignore — 로컬 전용, 커밋 안 됨)

- [ ] dev-log 항목 내용: 동기(수정계획서 §5) · ② 설정 한 줄과 테스트 3건 · ① 프로파일·fixedDelay 이유·로그 발췌 · ③ 테이블·REQUIRES_NEW·공개/관리자 뷰·장애 주입 결과 JSON · 안 한 것(ShedLock·경쟁 창) · 테스트 최종 개수(`./gradlew test` 결과).
- [ ] HANDOFF: 최종 갱신일, 30초 요약(9/14 추가된 것), 배포 상태(EC2 중지·재기동 안 함), 다음 작업(push·태그·서류 반영·daiso 대괄호), 환경(batch 프로파일 실행 명령).
- [ ] 커밋: `git add docs/dev-log.md && git commit -m "docs: dev-log 9/14 — 캐시 커밋 순서·정기 집계 로그·장애 주입(Mongo 중단) 실패→재실행 기록"`

---

### Task 6: README 9항목 반영

**Files:**
- Modify: `README.md`

- [ ] 상단(제목 아래): `> **서류 기준 커밋**: 태그 [\`submission-2026-09\`](https://github.com/aucu2005/scenelog/releases/tag/submission-2026-09) (2026-09-14) — 이후 커밋이 늘어도 심사 시점은 이 태그로 고정합니다.`
- [ ] 그래프 캡션: "배포 중인 서버" → "2026-08-02 배포 서버(EC2)의 API 응답으로 캡처".
- [ ] 데모 절 → "실행해 보기 (로컬 10분)" 절로 교체: 준비물(Docker Desktop, JDK 17, TMDB API 키 무료 발급), `.env` 예시, `docker compose --profile app up -d --build`, 대시보드/Swagger/health URL은 `localhost:8080`, 시연 순서(signup에 nickname 필수 → psql로 ROLE_ADMIN 승격 → login → Authorize → ETL `pages=1` → simulate → aggregate → timeline/highlights → 대시보드 새로고침 → 배치 실행 카드), 120만 건 재현은 `--spring.profiles.active=seed`. 배포 이력 한 문단: "2026-08-01~09 EC2 t3.micro에 Terraform으로 배포·운영(OOM 0회, 로그 7호). 심사 기간 비용 판단으로 2026-09 인스턴스를 중지했고 README 절차로 로컬 재현합니다." GIF 캡션도 캡처 시점 명시.
- [ ] 정량 성과 표 3행: `각본에 심은 정답 피크 2/2` → `골든셋 120회(12구조×10시드, 검증 시드 11~20) 하이라이트 검출 정밀도 31.6% → 98.5%, 재현율 98.2% → 97.6% (v1 → v2, 절대 하한 도입)` + 근거 `[dev-log 8/9](docs/dev-log.md)`; 캐시 수치 유지. 정직한 기록 문단: `단위 테스트 34개` → 최종 실측 개수.
- [ ] mermaid: `PG[(PostgreSQL<br/>contents·users·sessions<br/>segment_stats·highlights<br/>quality_reports·batch_runs)]`, `ETL -->|품질 리포트| PG`, `MG[(MongoDB<br/>rejected_records)]`, `SCHED["@Scheduled<br/>(batch 프로파일)"] -.->|정기| AGG`, `AGG -->|실행 이력| PG`.
- [ ] "왜 Kafka·Airflow를 안 쓰나": "Spring Scheduling으로 충분하다고 판단했고" → "정기 집계는 `batch` 프로파일의 `@Scheduled`(fixedDelay)로, 실행 이력은 `batch_runs` 표로 해결했고(dev-log 9/14 로컬 검증), 기본 프로파일은 관리자 API 수동 트리거만 둡니다. 다중 인스턴스 분산 락(ShedLock)은 미적용 — 인스턴스가 2대 이상이 되는 시점의 과제로 남깁니다."
- [ ] "왜 하이라이트 검출이 통계인가": ZSCORE_V1 → V2 서술(절대 하한 minLift=2.5, 튜닝/검증 시드 분리, V1 행 보존).
- [ ] 캐시 문장 추가(기술적 판단): "캐시 무효화는 DB 커밋 이후에 실행되도록 고정(`transactionAware`, 테스트 3건). 커밋 직전에 시작한 조회가 삭제 뒤 옛 값을 쓰는 경쟁은 남아 TTL 10분이 안전망."
- [ ] 한계 절: 데모 서버 중지, 분산 락 미적용, 캐시 경쟁 창, 구조 8 약한 피크 미검출(median/MAD 후속) 추가. 기존 항목 유지.
- [ ] 문서 절: 트러블슈팅 8건, 학습 가이드 6부작 + 프로젝트종합 가이드(7편), 스펙·계획 링크.
- [ ] 커밋: `git add README.md && git commit -m "docs(readme): 서류·코드와 숫자 동기화 — 테스트 N, 트러블슈팅 8, 가이드 7, 검출 v2 98.5/97.6, 데모 중지·로컬 재현 절차, 배치 운영"`

---

### Task 7: 어디다있소 README "최종 MVP 구조" 절 (`C:\Users\aucu2\Project\daiso`, dev)

- [ ] `## 🏗️ 시스템 아키텍처 (AWS Lightsail 2-Instance)` 절 바로 뒤에 `## 🧩 최종 MVP 구조 (2026-02-25) — 초기 배포와 무엇이 다른가` 절 추가. 내용: 초기 배포(2/23, main `docker-compose.prod.yml` qdrant·elasticsearch·backend·frontend 4서비스) → 최종 MVP(2/25, dev: `requirements.txt` "Removed: qdrant-client, elasticsearch, redis (Not used in MVP)", `search_service.py` ES 초기화 실패 시 LocalBM25 폴백, `pipeline.py` `QDRANT_URL`/`ELASTIC_URL` 있을 때만 붙는 선택 어댑터, `backend/database/chroma_db` 인덱스가 백엔드 이미지에 포함) 표 + 전환 이유("기대 성능이 나오지 않아") + 재측정 스니펫(`README_스니펫_수정안_2026-09-14.md` 그대로, 대괄호 유지).
- [ ] 구조 절만 먼저 커밋(`docs: 최종 MVP 구조(2/25) — 로컬 엔진 기본·외부 엔진 선택 어댑터, 초기 Lightsail 구성과의 차이`), 재측정 스니펫은 **커밋하지 않고 작업 트리에만** 둔다(gold.json 검수·해시 2건은 동국님 몫).

---

### Task 8: 마무리 — 전체 테스트, main 병합, 태그, 검증 보고

- [ ] `./gradlew test --console=plain` 최종 실행 → 총 개수·실패 0 확인 → README의 테스트 수와 일치 확인(`grep -n "개" README.md`).
- [ ] `git checkout main && git merge --ff-only feature/batch-ops-readme-0914 && git tag -a submission-2026-09 -m "하이스트레인저 지원 서류 기준 커밋 (2026-09-14)"`
- [ ] 보고: push 명령(`git push origin main --tags`)은 사용자 실행. 서류에 반영할 문장 목록(C-3·C-6 "할 때" 버전, 테스트 수, 가이드 수).
