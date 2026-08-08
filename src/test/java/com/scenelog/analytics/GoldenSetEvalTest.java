package com.scenelog.analytics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 골든셋 다양화 평가 — 고정(회귀 방지) 테스트.
 * 스펙: docs/superpowers/specs/2026-08-07-golden-set-precision-design.md,
 *       docs/superpowers/specs/2026-08-07-detector-v2-min-lift-design.md
 *
 * <p>v1 고정: minLift=0(하한 비활성) × 튜닝 시드 1~10 — 2026-08-07 실측값을 고정한다.
 * 검출기·시뮬레이터·집계기 동작이 바뀌면 여기가 깨진다.
 */
class GoldenSetEvalTest {

    @Test
    void 골든셋_v1_기준선_정밀도_재현율_고정() throws Exception {
        var r = GoldenSetHarness.evaluate(new HighlightDetector(0), GoldenSetHarness.TUNING_SEEDS);

        String report = GoldenSetHarness.reportTable(r);
        System.out.println(report);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/golden-set-eval.md"), report);

        // 구조 불변식 — 시나리오 정의가 스펙 §3과 일치하는가 (17피크 × 10시드)
        assertThat(r.totalAnswers()).isEqualTo(170);

        // 측정값 고정 (2026-08-07 실측) — 검출기 상수를 바꾸면 여기가 깨진다 (회귀 방지)
        assertThat(r.tp()).isEqualTo(184);
        assertThat(r.fp()).isEqualTo(398);
        assertThat(r.found()).isEqualTo(167);
    }

    @Test
    void 골든셋_v2_검증시드_정밀도_재현율_고정() throws Exception {
        // 공식 숫자 — 선정에 쓰지 않은 검증 시드 11~20 (스펙 detector-v2 §4-3)
        var r = GoldenSetHarness.evaluate(new HighlightDetector(), GoldenSetHarness.VALIDATION_SEEDS);

        String report = GoldenSetHarness.reportTable(r);
        System.out.println(report);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/golden-set-eval-v2.md"), report);

        assertThat(r.totalAnswers()).isEqualTo(170);

        // 측정값 고정 (2026-08-07 실측, 검증 시드 11~20)
        assertThat(r.tp()).isEqualTo(195);
        assertThat(r.fp()).isEqualTo(3);
        assertThat(r.found()).isEqualTo(166);
    }
}
