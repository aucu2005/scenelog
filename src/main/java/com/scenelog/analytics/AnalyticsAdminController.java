package com.scenelog.analytics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 집계+검출 트리거 (ROLE_ADMIN — /api/admin/** 인가는 SecurityConfig가 강제) */
@RestController
@RequestMapping("/api/admin/contents/{contentId}")
@RequiredArgsConstructor
@Tag(name = "관리자 · 집계", description = "집계 + 하이라이트 검출 실행 (멱등)")
public class AnalyticsAdminController {

    private final AggregationService aggregationService;

    @PostMapping("/aggregate")
    @Operation(summary = "집계 + 검출 실행",
            description = "전량 재계산이므로 몇 번을 실행해도 결과가 같다. 완료 시 타임라인 캐시를 무효화한다.")
    public Map<String, Object> aggregate(@PathVariable Long contentId) {
        return aggregationService.aggregate(contentId);
    }
}
