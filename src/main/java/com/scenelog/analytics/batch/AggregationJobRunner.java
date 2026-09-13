package com.scenelog.analytics.batch;

import com.scenelog.analytics.AggregationResult;
import com.scenelog.analytics.AggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 집계를 "실행 이력이 남는 잡"으로 감싼다. 관리자 API(MANUAL)와 스케줄러(SCHEDULED)가 모두 여기를 지난다.
 *
 * <p>예외는 삼키지 않는다 — 기록만 하고 그대로 전파해 호출자가 결정한다
 * (HTTP는 500, 스케줄러는 건별 격리 후 다음 콘텐츠로).
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
