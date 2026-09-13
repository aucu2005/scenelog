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
 * 배치 실행 1회 = 1행 (수정계획서 §5-4, 스펙 2026-09-14 §3-3).
 * "배치가 실패하면 어떻게 아세요?"에 대한 답이 이 표다.
 *
 * <p>실패 행도 남아야 하므로 기록은 집계 트랜잭션과 <b>분리된</b> 트랜잭션에서 쓴다 ({@link BatchRunRecorder}).
 * 공고 담당업무 8(모니터링/로깅 기반 문제 해결)에 대응한다.
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
