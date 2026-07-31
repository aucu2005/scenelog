package com.scenelog.analytics;

import com.scenelog.common.config.RedisConfig;
import com.scenelog.common.error.ApiException;
import com.scenelog.content.ContentRepository;
import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 집계 오케스트레이션: 원본 이벤트 → 버킷 집계 → 하이라이트 검출 → 저장 → 캐시 무효화.
 *
 * <p><b>멱등 = 전량 재계산 + 덮어쓰기</b> (기획서 §5.1):
 * 해당 콘텐츠의 기존 집계를 지우고 처음부터 다시 계산해 넣는다.
 * 증분 방식(마지막 처리 지점 기억)보다 느리지만, 몇 번을 실행해도 결과가 같음을
 * 증명하기 쉽고 워터마크 관리·이중 계산 위험이 없다.
 * 한계: 콘텐츠당 이벤트가 수백만 건 규모가 되면 증분으로 전환해야 한다 (README 한계 절).
 *
 * <p>delete 후 insert인 이유: UPSERT만 쓰면 "이번 계산에서 사라진 버킷"(이벤트가 지워진 경우)의
 * 옛 행이 남는다. 지우고 다시 넣으면 결과가 항상 현재 원본과 정확히 일치한다.
 */
@Service
@RequiredArgsConstructor
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);
    public static final int BUCKET_SIZE_SEC = 10;

    private final ReactionEventRepository reactionEventRepository;
    private final SegmentStatRepository segmentStatRepository;
    private final HighlightRepository highlightRepository;
    private final ContentRepository contentRepository;

    private final Aggregator aggregator = new Aggregator();
    private final HighlightDetector detector = new HighlightDetector();

    /**
     * {@code @CacheEvict} — 집계가 갱신되면 해당 콘텐츠의 타임라인 캐시를 지운다.
     * 이것이 없으면 캐시가 옛 집계를 계속 서빙한다 (무효화 없는 캐시는 stale 서빙 장치다).
     */
    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_TIMELINE, key = "#contentId")
    public Map<String, Object> aggregate(Long contentId) {
        if (!contentRepository.existsById(contentId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다: " + contentId);
        }

        List<ReactionEvent> events = reactionEventRepository.findByContentId(contentId);
        Map<Integer, BucketCounts> buckets = aggregator.aggregate(events, BUCKET_SIZE_SEC);

        // 전량 재계산: 지우고 다시 넣는다 (한 트랜잭션 안 — 중간 상태가 밖에 보이지 않는다)
        segmentStatRepository.deleteAllByContentId(contentId);
        segmentStatRepository.saveAll(buckets.entrySet().stream()
                .map(e -> new SegmentStat(contentId, e.getKey(), e.getValue()))
                .toList());

        // 검출 — 같은 방식(ZSCORE_V1)의 이전 결과만 교체
        TreeMap<Integer, Integer> totals = buckets.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().total(), (a, b) -> a, TreeMap::new));
        List<HighlightWindow> windows = detector.detect(totals, BUCKET_SIZE_SEC);

        highlightRepository.deleteAllByContentIdAndMethod(contentId, Highlight.METHOD_ZSCORE_V1);
        highlightRepository.saveAll(windows.stream()
                .map(w -> new Highlight(contentId, w, Highlight.METHOD_ZSCORE_V1))
                .toList());

        log.info("집계 완료 — contentId={}, 이벤트 {}건 → 버킷 {}개, 하이라이트 {}개",
                contentId, events.size(), buckets.size(), windows.size());

        return Map.of(
                "contentId", contentId,
                "events", events.size(),
                "buckets", buckets.size(),
                "highlights", windows.stream()
                        .map(w -> Map.of("startSec", w.startSec(), "endSec", w.endSec(),
                                "score", Math.round(w.score() * 1000) / 1000.0))
                        .toList());
    }
}
