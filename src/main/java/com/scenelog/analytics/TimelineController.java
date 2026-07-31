package com.scenelog.analytics;

import com.scenelog.analytics.dto.HighlightResponse;
import com.scenelog.analytics.dto.TimelineBucketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/contents/{contentId}")
@RequiredArgsConstructor
@Tag(name = "타임라인 · 하이라이트", description = "집계 결과 조회 (공개, Redis 캐시)")
public class TimelineController {

    private final TimelineService timelineService;

    @GetMapping("/timeline")
    @Operation(summary = "반응 타임라인", description = "10초 버킷별 반응 카운트. 첫 조회 후 Redis 캐시.")
    public List<TimelineBucketResponse> timeline(@PathVariable Long contentId) {
        return timelineService.timeline(contentId);
    }

    @GetMapping("/highlights")
    @Operation(summary = "하이라이트 구간", description = "z-score 검출 결과 (method=ZSCORE_V1)")
    public List<HighlightResponse> highlights(@PathVariable Long contentId) {
        return timelineService.highlights(contentId);
    }
}
