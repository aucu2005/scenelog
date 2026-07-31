package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    private final Aggregator aggregator = new Aggregator();

    private ReactionEvent at(int offsetSec, ReactionType type) {
        return new ReactionEvent("e-" + offsetSec + "-" + type, 1L, 1L, 1L, offsetSec, type, Instant.EPOCH);
    }

    @Test
    void 같은_10초_버킷의_이벤트는_유형별로_카운트된다() {
        List<ReactionEvent> events = List.of(
                at(2431, ReactionType.TENSION),
                at(2437, ReactionType.TENSION),
                at(2433, ReactionType.LAUGH),
                at(2440, ReactionType.TOUCHED)   // 다음 버킷(2440)
        );
        Map<Integer, BucketCounts> result = aggregator.aggregate(events, 10);

        BucketCounts b2430 = result.get(2430);
        assertThat(b2430.tension()).isEqualTo(2);
        assertThat(b2430.laugh()).isEqualTo(1);
        assertThat(b2430.touched()).isZero();
        assertThat(b2430.total()).isEqualTo(3);
        assertThat(result.get(2440).touched()).isEqualTo(1);
    }

    @Test
    void 두_번_실행해도_결과가_같다() {  // 전량 재계산 멱등성의 근거 (기획서 §5.1)
        List<ReactionEvent> events = List.of(at(5, ReactionType.LAUGH), at(15, ReactionType.BORED));
        assertThat(aggregator.aggregate(events, 10)).isEqualTo(aggregator.aggregate(events, 10));
    }

    @Test
    void 빈_입력은_빈_맵이다() {
        assertThat(aggregator.aggregate(List.of(), 10)).isEmpty();
    }

    @Test
    void 버킷_경계값은_자기_버킷에_들어간다() {  // offset 2440 → 버킷 2440 (2430 아님)
        Map<Integer, BucketCounts> result =
                aggregator.aggregate(List.of(at(2440, ReactionType.LAUGH)), 10);
        assertThat(result).containsOnlyKeys(2440);
    }
}
