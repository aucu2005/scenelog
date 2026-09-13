package com.scenelog.analytics.batch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatchRunRepository extends JpaRepository<BatchRun, Long> {

    /** 최신 실행부터 — 대시보드 "최근 배치 실행" 카드와 조회 API가 쓴다 */
    List<BatchRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
