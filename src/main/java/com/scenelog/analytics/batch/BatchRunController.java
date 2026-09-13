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

/**
 * 배치 실행 이력 조회. 공개(대시보드)는 오류 타입까지, 관리자는 메시지까지.
 * 공개 경로의 인가는 SecurityConfig(GET /api/batch-runs permitAll), 관리자 경로는 /api/admin/** 규칙.
 */
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
