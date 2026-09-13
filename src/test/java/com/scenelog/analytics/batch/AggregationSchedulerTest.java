package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정기 집계의 두 가지 약속: 이벤트가 있는 콘텐츠를 전부 돌고, 하나가 실패해도 나머지는 계속 돈다.
 * 집계는 {@link AggregationJobRunner}(실행 이력 기록)를 통과한다 — 트리거는 SCHEDULED.
 */
@ExtendWith(MockitoExtension.class)
class AggregationSchedulerTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock AggregationJobRunner jobRunner;
    @InjectMocks AggregationScheduler scheduler;

    private void targets(Long... ids) {
        when(mongoTemplate.findDistinct(any(Query.class), eq("contentId"), eq(ReactionEvent.class), eq(Long.class)))
                .thenReturn(List.of(ids));
    }

    @Test
    void 이벤트가_있는_모든_콘텐츠를_집계한다() {
        targets(1L, 2L);

        AggregationScheduler.RunSummary s = scheduler.runOnce();

        verify(jobRunner).run(1L, BatchTrigger.SCHEDULED);
        verify(jobRunner).run(2L, BatchTrigger.SCHEDULED);
        assertThat(s.targets()).isEqualTo(2);
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.failed()).isZero();
    }

    @Test
    void 한_콘텐츠의_실패가_나머지_집계를_막지_않는다() {
        targets(1L, 2L, 3L);
        // strict stubs 환경에서 인자별 동작을 한 스텁으로 — 2번만 실패, 나머지는 정상 반환
        when(jobRunner.run(anyLong(), eq(BatchTrigger.SCHEDULED))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (id == 2L) throw new RuntimeException("mongo down");
            return new AggregationResult(id, 0, 0, List.of());
        });

        AggregationScheduler.RunSummary s = scheduler.runOnce();

        verify(jobRunner).run(3L, BatchTrigger.SCHEDULED);   // 2번이 실패해도 3번은 돈다
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(1);
    }
}
