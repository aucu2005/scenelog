package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import com.scenelog.reaction.sim.Peak;
import com.scenelog.reaction.sim.ReactionSimulator;
import com.scenelog.reaction.sim.Scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 골든셋 평가 공통 하네스 — 구조 12종 정의와 (시뮬레이터→집계→검출→채점) 실행 루프.
 * 고정 테스트(GoldenSetEvalTest)·스윕 러너(MinLiftSweepEvalTest)가 공유한다.
 * 구조 파라미터는 스펙 2026-08-07-golden-set-precision-design.md §3 그대로 — 수정 금지.
 */
final class GoldenSetHarness {

    static final int USERS = 20;
    static final int BUCKET_SEC = 10;
    /** 튜닝(상수 선정)용과 검증(공식 숫자)용 시드를 분리한다 — 자기 채점 방지 (스펙 detector-v2 §4) */
    static final long[] TUNING_SEEDS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    static final long[] VALIDATION_SEEDS = {11, 12, 13, 14, 15, 16, 17, 18, 19, 20};

    record Structure(String name, Scenario scenario) {}

    record StructureResult(String name, int found, int answers, int fp) {}

    record EvalResult(int tp, int fp, int found, int missed, List<StructureResult> byStructure) {
        int totalAnswers() { return found + missed; }
        double precision() { return (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp); }
        double recall() { return (double) found / totalAnswers(); }
    }

    private GoldenSetHarness() {}

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

    static EvalResult evaluate(HighlightDetector detector, long[] seeds) {
        var simulator = new ReactionSimulator();
        var aggregator = new Aggregator();

        int tp = 0, fp = 0, found = 0, missed = 0;
        List<StructureResult> byStructure = new ArrayList<>();

        for (Structure s : goldenSet()) {
            int sFound = 0, sAnswers = 0, sFp = 0;
            for (long seed : seeds) {
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
            byStructure.add(new StructureResult(s.name(), sFound, sAnswers, sFp));
        }
        return new EvalResult(tp, fp, found, missed, byStructure);
    }

    static String reportTable(EvalResult r) {
        var sb = new StringBuilder("| 구조 | 발견/정답 | FP |\n|---|---|---|\n");
        for (StructureResult s : r.byStructure()) {
            sb.append("| %s | %d/%d | %d |\n".formatted(s.name(), s.found(), s.answers(), s.fp()));
        }
        sb.append("\n합산: TP(검출)=%d, FP=%d, 발견=%d/%d — 정밀도 %.1f%%, 재현율 %.1f%%\n"
                .formatted(r.tp(), r.fp(), r.found(), r.totalAnswers(), r.precision() * 100, r.recall() * 100));
        return sb.toString();
    }
}
