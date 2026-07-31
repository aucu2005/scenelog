package com.scenelog.analytics.dto;

import com.scenelog.analytics.Highlight;

/** 검출된 하이라이트 구간 응답 */
public record HighlightResponse(int startSec, int endSec, double score, String method) {

    public static HighlightResponse from(Highlight h) {
        return new HighlightResponse(h.getStartSec(), h.getEndSec(), h.getScore(), h.getMethod());
    }
}
