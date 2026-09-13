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
 *
 * <p>호출자({@link AggregationJobRunner})는 트랜잭션이 없고 집계 서비스만 트랜잭션을 연다. 그래도
 * REQUIRES_NEW를 쓰는 이유: 누군가 러너를 트랜잭션 안에서 호출해도(테스트·향후 배치 래퍼) 기록이
 * 그 트랜잭션에 딸려 들어가지 않게 — "기록은 항상 독립"이라는 계약을 코드에 고정한다.
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
