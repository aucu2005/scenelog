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

/** 집계를 "실행 이력이 남는 잡"으로 감싼다 — 성공이든 실패든 기록하고, 예외는 삼키지 않는다. */
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
