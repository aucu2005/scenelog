package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import com.scenelog.reaction.sim.Peak;
import com.scenelog.reaction.sim.ReactionSimulator;
import com.scenelog.reaction.sim.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HighlightDetectorTest {

    private final HighlightDetector detector = new HighlightDetector();

    /** 편의: int[] → totalsByBucket (10초 간격) */
    private TreeMap<Integer, Integer> buckets(int... totals) {
        TreeMap<Integer, Integer> m = new TreeMap<>();
        for (int i = 0; i < totals.length; i++) m.put(i * 10, totals[i]);
        return m;
    }

    @Test
    void 평평한_데이터에서는_아무것도_검출하지_않는다() {
        assertThat(detector.detect(buckets(5, 5, 5, 5, 5, 5, 5, 5), 10)).isEmpty();
    }

    @Test
    void 명확한_스파이크를_검출한다() {
        // 버킷 10~11(100~120초)에 스파이크. 기준선을 충분히 길게(20버킷) 둔 이유:
        // 시계열이 짧으면 피크 자신이 표준편차를 부풀려 자기 z-score를 깎는다
        // (12버킷에서는 z=1.96으로 임계값 2.0에 미달하는 것을 실측) — z-score의 통계적 성질.
        List<HighlightWindow> result = detector.detect(
                buckets(4, 5, 4, 6, 5, 4, 5, 6, 4, 5, 60, 55, 5, 4, 6, 5, 4, 5, 6, 5), 10);
        assertThat(result).hasSize(1);
        HighlightWindow w = result.get(0);
        assertThat(w.startSec()).isLessThanOrEqualTo(100);
        assertThat(w.endSec()).isGreaterThanOrEqualTo(120);
        assertThat(w.score()).isGreaterThan(HighlightDetector.Z_THRESHOLD);
    }

    @Test
    void 버킷이_너무_적으면_빈_결과다() {
        assertThat(detector.detect(buckets(1, 50, 1), 10)).isEmpty();
    }

    @Test
    void 시뮬레이터_각본의_정답_피크를_찾아낸다() {  // ★ §5-B-5 — 정확도 측정의 원형
        Scenario inception = new Scenario(1L, 8880, 0.5, List.of(
                new Peak(2400, 2450, ReactionType.TENSION, 15.0),
                new Peak(6100, 6160, ReactionType.TOUCHED, 12.0)
        ));
        List<ReactionEvent> events = new ReactionSimulator().generate(inception, 50, 42L);
        Map<Integer, BucketCounts> bucketMap = new Aggregator().aggregate(events, 10);
        TreeMap<Integer, Integer> totals = bucketMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().total(), (a, b) -> a, TreeMap::new));

        List<HighlightWindow> detected = detector.detect(totals, 10);

        for (Peak answer : inception.peaks()) {   // 정답 피크마다 ±50초 안에 검출이 있어야 한다
            assertThat(detected).anySatisfy(w ->
                    assertThat(w.startSec()).isBetween(answer.startSec() - 50, answer.startSec() + 50));
        }
    }
}
