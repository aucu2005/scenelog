package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import com.scenelog.reaction.sim.Peak;
import com.scenelog.reaction.sim.ReactionSimulator;
import com.scenelog.reaction.sim.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 골든셋 다양화 평가 (스펙: docs/superpowers/specs/2026-08-07-golden-set-precision-design.md).
 *
 * <p>구조 12종 × 시드 10 = 120회. 운영과 동일한 클래스 경로
 * (ReactionSimulator → Aggregator → HighlightDetector)를 메모리에서 돌린다.
 * 결과표는 콘솔과 build/reports/golden-set-eval.md에 남는다.
 *
 * <p>합산 실측값을 assert로 고정한다 — 검출기 상수(Z_THRESHOLD 등)를 바꾸면
 * 이 테스트가 깨져서, 평가가 곧 회귀 방지가 된다.
 */
class GoldenSetEvalTest {

    static final int USERS = 20;
    static final int BUCKET_SEC = 10;
    static final long[] SEEDS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    /** 스펙 §3의 구조 12종. 이름 앞 번호는 스펙 표의 # */
    record Structure(String name, Scenario scenario) {}

    static List<Structure> goldenSet() {
        return List.of(
                new Structure("1 무피크·짧은", new Scenario(1L, 5_400, 0.5, List.of())),
                new Structure("2 무피크·긴", new Scenario(2L, 10_800, 0.5, List.of())),
                new Structure("3 강한 피크 1개", new Scenario(3L, 7_200, 0.5, List.of(
                        new Peak(2_160, 2_210, ReactionType.TENSION, 15.0)))),
                new Structure("4 약한 피크 1개", new Scenario(4L, 7_200, 0.5, List.of(
                        new Peak(3_600, 3_650, ReactionType.TENSION, 4.0)))),
                new Structure("5 시작 직후 피크", new Scenario(5L, 7_200, 0.5, List.of(
                        new Peak(30, 80, ReactionType.TENSION, 15.0)))),
                new Structure("6 기본형", new Scenario(6L, 7_200, 0.5, List.of(
                        new Peak(1_944, 1_994, ReactionType.TENSION, 15.0),
                        new Peak(4_896, 4_956, ReactionType.TOUCHED, 12.0)))),
                new Structure("7 인접 피크 30s", new Scenario(7L, 7_200, 0.5, List.of(
                        new Peak(3_000, 3_050, ReactionType.TENSION, 12.0),
                        new Peak(3_080, 3_130, ReactionType.TENSION, 12.0)))),
                new Structure("8 강+약 혼합", new Scenario(8L, 7_200, 0.5, List.of(
                        new Peak(2_000, 2_050, ReactionType.TENSION, 15.0),
                        new Peak(5_000, 5_050, ReactionType.TENSION, 4.0)))),
                new Structure("9 3피크 균등", new Scenario(9L, 9_000, 0.5, List.of(
                        new Peak(1_800, 1_850, ReactionType.TENSION, 12.0),
                        new Peak(4_500, 4_550, ReactionType.TENSION, 12.0),
                        new Peak(7_200, 7_250, ReactionType.TENSION, 12.0)))),
                new Structure("10 3피크 후반", new Scenario(10L, 9_000, 0.5, List.of(
                        new Peak(6_300, 6_350, ReactionType.TENSION, 12.0),
                        new Peak(7_200, 7_250, ReactionType.TENSION, 12.0),
                        new Peak(8_100, 8_150, ReactionType.TENSION, 12.0)))),
                new Structure("11 고노이즈", new Scenario(11L, 7_200, 2.0, List.of(
                        new Peak(2_880, 2_930, ReactionType.TENSION, 15.0)))),
                new Structure("12 완만 5분", new Scenario(12L, 7_200, 0.5, List.of(
                        new Peak(3_300, 3_600, ReactionType.TOUCHED, 3.0))))
        );
    }

    @Test
    void 골든셋_120회_정밀도_재현율_측정() throws Exception {
        var simulator = new ReactionSimulator();
        var aggregator = new Aggregator();
        var detector = new HighlightDetector();

        int tp = 0, fp = 0, found = 0, missed = 0;
        var report = new StringBuilder("| 구조 | 발견/정답 | FP |\n|---|---|---|\n");

        for (Structure s : goldenSet()) {
            int sFound = 0, sAnswers = 0, sFp = 0;
            for (long seed : SEEDS) {
                List<ReactionEvent> events = simulator.generate(s.scenario(), USERS, seed);
                Map<Integer, BucketCounts> buckets = aggregator.aggregate(events, BUCKET_SEC);
                TreeMap<Integer, Integer> totals = buckets.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().total(), (a, b) -> a, TreeMap::new));

                var sc = GoldenSetScorer.score(detector.detect(totals, BUCKET_SEC), s.scenario().peaks());
                tp += sc.tpDetections(); fp += sc.fp();
                found += sc.foundPeaks(); missed += sc.missedPeaks();
                sFound += sc.foundPeaks(); sFp += sc.fp();
                sAnswers += s.scenario().peaks().size();
            }
            report.append("| %s | %d/%d | %d |\n".formatted(s.name(), sFound, sAnswers, sFp));
        }

        int totalAnswers = found + missed;
        double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = (double) found / totalAnswers;
        report.append("\n합산: TP(검출)=%d, FP=%d, 발견=%d/%d — 정밀도 %.1f%%, 재현율 %.1f%%\n"
                .formatted(tp, fp, found, totalAnswers, precision * 100, recall * 100));

        System.out.println(report);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/golden-set-eval.md"), report.toString());

        // 구조 불변식 — 시나리오 정의가 스펙 §3과 일치하는가 (17피크 × 10시드)
        assertThat(totalAnswers).isEqualTo(170);

        // ── 측정값 고정 (Task 3에서 실측 후 아래 주석을 실제 assert로 교체) ──
        // assertThat(tp).isEqualTo(???);
        // assertThat(fp).isEqualTo(???);
        // assertThat(found).isEqualTo(???);
    }
}
