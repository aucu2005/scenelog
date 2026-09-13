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

    /** 공개 뷰 — 오류는 예외 타입까지만 (메시지에 내부 호스트명 등이 섞일 수 있다) */
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
