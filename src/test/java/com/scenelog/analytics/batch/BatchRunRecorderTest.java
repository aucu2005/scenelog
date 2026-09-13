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

/**
 * 실행 이력은 집계 트랜잭션과 운명을 같이하지 않는다 (REQUIRES_NEW, 스펙 2026-09-14 §3-3).
 * 실제 PostgreSQL 사용 — ddl-auto=update가 batch_runs 테이블을 만든다.
 */
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
