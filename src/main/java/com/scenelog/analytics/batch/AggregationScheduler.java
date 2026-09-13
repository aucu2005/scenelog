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
 * <p><b>fixedDelay</b> = 이전 실행이 <b>끝난 시점</b>부터 대기. 120만 건 재집계가 주기보다 오래 걸려도
 * 겹쳐 돌지 않는다(fixedRate라면 겹친다). 대상은 reaction_events의 distinct contentId —
 * (contentId, offsetSec) 복합 인덱스의 접두 컬럼이라 인덱스만 읽는다.
 *
 * <p>콘텐츠 하나의 실패가 나머지를 막지 않도록 건별로 격리한다. 다중 인스턴스 동시 실행 방지
 * (ShedLock 등)는 미적용 — 단일 인스턴스 전제이며, 인스턴스가 늘 때 필요한 항목으로 README에 기록한다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class AggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AggregationScheduler.class);

    private final MongoTemplate mongoTemplate;
    private final AggregationService aggregationService;

    /** 한 번의 정기 실행 요약 — 로그 한 줄과 테스트의 단언 대상 */
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
