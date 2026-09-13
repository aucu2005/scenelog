package com.scenelog.analytics;

import java.util.List;
import java.util.Map;

/**
 * 집계 1회의 결과 (2026-09-14).
 * 실행 이력(batch_runs)이 처리 건수를 <b>타입으로</b> 읽기 위해 Map 대신 레코드로 바꿨다.
 * 관리자 API 응답 JSON은 {@link #toResponse()}로 이전과 같은 모양을 유지한다.
 */
public record AggregationResult(Long contentId, int events, int buckets, List<HighlightWindow> highlights) {

    public Map<String, Object> toResponse() {
        return Map.of(
                "contentId", contentId,
                "events", events,
                "buckets", buckets,
                "highlights", highlights.stream()
                        .map(w -> Map.of("startSec", w.startSec(), "endSec", w.endSec(),
                                "score", Math.round(w.score() * 1000) / 1000.0))
                        .toList());
    }
}
