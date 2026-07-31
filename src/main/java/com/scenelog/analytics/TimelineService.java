package com.scenelog.analytics;

import com.scenelog.analytics.dto.HighlightResponse;
import com.scenelog.analytics.dto.TimelineBucketResponse;
import com.scenelog.common.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 타임라인·하이라이트 조회 (기획서 §5-A-4 ⑤).
 *
 * <p>조회 경로에는 계산이 없다 — 집계 배치가 미리 만들어 둔 segment_stats를 읽을 뿐이다
 * (기획서 §4 설계원칙 1: 조회 경로에 동기 분석 호출 없음).
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final SegmentStatRepository segmentStatRepository;
    private final HighlightRepository highlightRepository;

    /**
     * {@code @Cacheable} — 첫 조회는 DB에서 읽어 Redis에 저장, 이후는 Redis에서 바로 반환.
     * 집계가 다시 돌면 {@code AggregationService}의 {@code @CacheEvict}가 이 키를 지운다.
     */
    @Cacheable(value = RedisConfig.CACHE_TIMELINE, key = "#contentId")
    @Transactional(readOnly = true)
    public List<TimelineBucketResponse> timeline(Long contentId) {
        return segmentStatRepository.findByContentIdOrderByBucketStartSec(contentId)
                .stream().map(TimelineBucketResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<HighlightResponse> highlights(Long contentId) {
        return highlightRepository.findByContentIdOrderByStartSec(contentId)
                .stream().map(HighlightResponse::from).toList();
    }
}
