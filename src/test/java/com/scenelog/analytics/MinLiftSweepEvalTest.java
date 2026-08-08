package com.scenelog.analytics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIN_LIFT 후보 스윕 — 튜닝 시드 1~10 전용 (검증 시드는 여기 쓰지 않는다: 자기 채점 방지).
 * 선정 도구일 뿐 회귀 게이트가 아니다 — 고정 assert는 k=0 기준선 재현 게이트뿐.
 * 선정 기준(스펙 detector-v2 §4-2): 재현율 ≥97%(발견 165/170↑) 유지 중 정밀도 최대, 동률이면 작은 k.
 */
class MinLiftSweepEvalTest {

    static final double[] CANDIDATES = {0, 1.25, 1.5, 2.0, 2.5, 3.0};

    @Test
    void 튜닝_시드에서_minLift_후보_스윕() throws Exception {
        var report = new StringBuilder("# MIN_LIFT 스윕 (튜닝 시드 1~10, 구조 12종)\n\n");
        report.append("| k | TP | FP | 발견/정답 | 정밀도 | 재현율 |\n|---|---|---|---|---|---|\n");

        for (double k : CANDIDATES) {
            var r = GoldenSetHarness.evaluate(new HighlightDetector(k), GoldenSetHarness.TUNING_SEEDS);
            report.append("| %.2f | %d | %d | %d/%d | %.1f%% | %.1f%% |\n".formatted(
                    k, r.tp(), r.fp(), r.found(), r.totalAnswers(), r.precision() * 100, r.recall() * 100));

            if (k == 0) {   // 기준선 재현 게이트 (스펙 §4-1) — v1 실측과 다르면 스윕 전체가 무효
                assertThat(r.tp()).isEqualTo(184);
                assertThat(r.fp()).isEqualTo(398);
                assertThat(r.found()).isEqualTo(167);
            }
        }

        System.out.println(report);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/min-lift-sweep.md"), report.toString());
    }
}
