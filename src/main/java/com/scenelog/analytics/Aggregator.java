package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 이벤트 → 버킷별 카운트 (기획서 §5-A-4 ③).
 *
 * <p>순수 함수라 같은 입력은 항상 같은 출력 — "전량 재계산 + 덮어쓰기" 멱등 전략(§5.1)의
 * 재계산 절반을 담당한다. 나머지 절반(덮어쓰기)은 AggregationService가 DB에서 수행한다.
 */
public class Aggregator {

    public Map<Integer, BucketCounts> aggregate(Collection<ReactionEvent> events, int bucketSizeSec) {
        Map<Integer, BucketCounts> buckets = new HashMap<>();
        for (ReactionEvent e : events) {
            int bucketStart = (e.offsetSec() / bucketSizeSec) * bucketSizeSec;
            buckets.merge(bucketStart, BucketCounts.ZERO.plus(e.reactionType()),
                    (old, unused) -> old.plus(e.reactionType()));
        }
        return buckets;
    }
}
