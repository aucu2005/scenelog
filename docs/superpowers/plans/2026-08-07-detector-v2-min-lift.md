# 검출기 v2 (절대 하한 MIN_LIFT) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HighlightDetector에 절대 하한(minLift)을 결합해 정밀도를 개선하고, 스윕(튜닝 시드 1~10)으로 상수를 선정한 뒤 검증 시드(11~20)로 공식 숫자를 실측한다.

**Architecture:** 검출 조건을 `z ≥ 2.0 AND smoothed ≥ mean × minLift`의 2단으로 확장한다(minLift=0 = v1 동일 동작). 평가 공통 로직을 `GoldenSetHarness`로 추출해 기존 고정 테스트·스윕 러너·v2 검증이 공유한다. 알고리즘 변경은 `ZSCORE_V2`로 버저닝하고 조회에 method 필터를 추가한다.

**Tech Stack:** Java 21, Gradle(JUnit 5, AssertJ). Spring 컨텍스트·DB는 Task 4의 선택적 검증에서만.

**스펙:** `docs/superpowers/specs/2026-08-07-detector-v2-min-lift-design.md`

## Global Constraints

- **브랜치 `feature/detector-v2-min-lift`에서만 작업** — main·EC2·서류(`../이력서-숫자문장-초안.md`) 무접촉. push는 사용자 결정.
- `Z_THRESHOLD = 2.0`·`MOVING_AVG_WINDOW`·`MIN_BUCKETS` 무변경 — 이번 변수는 minLift 하나다.
- 커밋 메시지는 한국어, `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` 푸터.
- 모든 명령은 `C:\Users\aucu2\Project\movieprojcet\scenelog`(Git Bash `/c/Users/aucu2/Project/movieprojcet/scenelog`)에서.
- MIN_LIFT 선정 기준 (스펙 §4-2, 기계적 적용): **튜닝 시드에서 재현율 ≥ 97%(발견 165/170 이상)를 유지하는 k 중 정밀도 최대. 동률이면 작은 k.**
- 공식 숫자 = **검증 시드 11~20 실측**. 튜닝 시드 숫자는 선정 근거로만.
- v1 기준선(minLift=0, 시드 1~10) = **tp 184 / fp 398 / found 167** — 재현 안 되면 중단·보고.
- `ScenelogApplicationTests.contextLoads`는 로컬 Docker(PostgreSQL) 필요 — Docker 비가동이면 실패가 정상(사전 존재 환경 이슈). 나머지 테스트 green이 기준.

---

### Task 1: HighlightDetector minLift 주입 (TDD)

**Files:**
- Modify: `src/main/java/com/scenelog/analytics/HighlightDetector.java`
- Test: `src/test/java/com/scenelog/analytics/HighlightDetectorTest.java` (테스트 2개 추가, 기존 4개 무수정)

**Interfaces:**
- Produces: `HighlightDetector()` (기본 생성자, `DEFAULT_MIN_LIFT` 사용 — 이 태스크에서는 임시 0.0), `HighlightDetector(double minLift)`, `public static final double DEFAULT_MIN_LIFT`. Task 2·3이 `new HighlightDetector(k)`를 사용한다.
- `detect(SortedMap<Integer,Integer>, int)` 시그니처는 무변경.

- [ ] **Step 1: 실패하는 테스트 작성**

`HighlightDetectorTest.java`에 다음 2개 테스트를 추가한다 (기존 테스트는 건드리지 않는다):

```java
    @Test
    void 절대량이_하한_미달이면_z가_넘어도_검출하지_않는다() {
        // 낮은 분산 위의 작은 융기: 스무딩 후 z≈2.7로 임계값(2.0)은 넘지만
        // 절대량은 평균의 1.1배뿐 — v2의 하한(minLift=2.0)이 걸러야 한다.
        TreeMap<Integer, Integer> smallBump = buckets(
                10, 10, 10, 10, 10, 10, 10, 10, 10, 12, 12, 10, 10, 10, 10, 10, 10, 10, 10, 10);
        assertThat(new HighlightDetector(0).detect(smallBump, 10)).hasSize(1);   // 하한 없으면 잡힘 = z는 실제로 넘는다
        assertThat(new HighlightDetector(2.0).detect(smallBump, 10)).isEmpty();  // 하한이 걸러낸다
    }

    @Test
    void minLift_0은_하한_비활성_v1_동작이다() {
        // v1 대조: 기존 '명확한 스파이크' 케이스와 동일 결과 (smoothed >= mean*0 은 항상 참)
        List<HighlightWindow> result = new HighlightDetector(0).detect(
                buckets(4, 5, 4, 6, 5, 4, 5, 6, 4, 5, 60, 55, 5, 4, 6, 5, 4, 5, 6, 5), 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).startSec()).isLessThanOrEqualTo(100);
        assertThat(result.get(0).endSec()).isGreaterThanOrEqualTo(120);
    }
```

- [ ] **Step 2: 실패 확인 (컴파일 에러 = RED)**

Run: `./gradlew test --tests "com.scenelog.analytics.HighlightDetectorTest"`
Expected: **FAIL** — `HighlightDetector(double)` 생성자가 없어 컴파일 에러. 이것이 RED다.

- [ ] **Step 3: 구현**

`HighlightDetector.java`의 필드·생성자 영역(클래스 선언 직후)을 다음으로 교체·추가:

```java
    static final int MOVING_AVG_WINDOW = 3;   // 중심 이동평균 창 (홀수)
    public static final double Z_THRESHOLD = 2.0;
    static final int MIN_BUCKETS = 6;         // 이하면 통계가 무의미 → 빈 결과

    /**
     * 절대 하한 배수 — 스무딩 값이 (전체 평균 × minLift) 이상이어야 하이라이트 후보다.
     * 골든셋 실측(2026-08-07)에서 FP 398건이 무피크·약한 신호 콘텐츠에 집중된 원인이
     * z-score의 상대성(절대 높이 하한 부재)으로 규명되어 도입 — 스펙 detector-v2-min-lift.
     * 0이면 하한 비활성 = v1(ZSCORE_V1)과 동일 동작.
     * 값은 임시 0.0 — 스윕 선정(튜닝 시드 1~10, 스펙 §4) 후 Task 3에서 갱신된다.
     */
    public static final double DEFAULT_MIN_LIFT = 0.0;

    private final double minLift;

    public HighlightDetector() {
        this(DEFAULT_MIN_LIFT);
    }

    public HighlightDetector(double minLift) {
        this.minLift = minLift;
    }
```

판정 루프의 조건 한 줄을 교체:

```java
            double z = (smoothed[k] - mean) / std;
            if (z >= Z_THRESHOLD && smoothed[k] >= mean * minLift) {
```

(기존: `if (z >= Z_THRESHOLD) {` — else-분기·병합 로직은 무변경)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.HighlightDetectorTest"`
Expected: **PASS** — 기존 4 + 신규 2 = 6개 green. DEFAULT_MIN_LIFT=0이라 기존 테스트는 동작 불변으로 통과해야 한다 — 하나라도 깨지면 중단·보고 (스펙 §5 하위호환 신호).

- [ ] **Step 5: 전체 단위 테스트 확인 후 커밋**

Run: `./gradlew test` — contextLoads(도커 필요) 외 전부 green 확인.

```bash
git add src/main/java/com/scenelog/analytics/HighlightDetector.java src/test/java/com/scenelog/analytics/HighlightDetectorTest.java
git commit -m "feat: 검출기 절대 하한(minLift) 주입 - 기본값 임시 0(v1 동작), 스윕 선정 대기

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: GoldenSetHarness 추출 + MIN_LIFT 스윕 러너

**Files:**
- Create: `src/test/java/com/scenelog/analytics/GoldenSetHarness.java`
- Create: `src/test/java/com/scenelog/analytics/MinLiftSweepEvalTest.java`
- Modify: `src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java` (하네스 사용으로 리팩터, 고정값 무변경)

**Interfaces:**
- Consumes: `HighlightDetector(double minLift)` (Task 1), `GoldenSetScorer.score(...)` → `Score(tpDetections, fp, foundPeaks, missedPeaks)` (기존).
- Produces: `GoldenSetHarness.TUNING_SEEDS`(1~10)·`VALIDATION_SEEDS`(11~20), `GoldenSetHarness.evaluate(HighlightDetector, long[] seeds)` → `EvalResult(tp, fp, found, missed, byStructure)` (+ `totalAnswers()`, `precision()`, `recall()`), `GoldenSetHarness.reportTable(EvalResult)` → 구조별 markdown 표 String. Task 3이 그대로 사용.
- 산출물: `build/reports/min-lift-sweep.md` (Task 3의 선정 입력).

- [ ] **Step 1: GoldenSetHarness 작성 (기존 GoldenSetEvalTest의 구조·루프를 이동)**

`GoldenSetHarness.java` 전체:

```java
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
```

- [ ] **Step 2: GoldenSetEvalTest를 하네스 사용으로 리팩터 (동작 보존 게이트)**

`GoldenSetEvalTest.java` 전체를 다음으로 교체 — 구조 정의·루프가 하네스로 이동했고, 검출기는 명시적 `new HighlightDetector(0)`(v1)이며, **고정값은 그대로**다:

```java
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
}
```

- [ ] **Step 3: 리팩터 검증 — 고정값이 그대로 재현되는가**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetEvalTest"`
Expected: **PASS** — 184/398/167 재현. 실패하면 하네스 이동 중 실수 — 중단·수정 (구조 파라미터를 스펙 §3과 재대조).

- [ ] **Step 4: 스윕 러너 작성**

`MinLiftSweepEvalTest.java` 전체:

```java
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
```

- [ ] **Step 5: 스윕 실행, 표 확보**

Run: `./gradlew test --tests "com.scenelog.analytics.MinLiftSweepEvalTest"`
Expected: **PASS** (k=0 게이트 통과). `build/reports/min-lift-sweep.md`의 표 전문을 리포트 파일에 복사한다 — Task 3의 선정 입력이다. 6개 후보 × 120회 = 720회지만 전부 메모리 연산이라 수십 초 내가 정상.

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/scenelog/analytics/GoldenSetHarness.java src/test/java/com/scenelog/analytics/MinLiftSweepEvalTest.java src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java
git commit -m "test: 골든셋 하네스 추출 + MIN_LIFT 스윕 러너 (튜닝 시드 1~10)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: MIN_LIFT 선정 + 검증 시드 실측 + v2 고정

**Files:**
- Modify: `src/main/java/com/scenelog/analytics/HighlightDetector.java` (DEFAULT_MIN_LIFT 0.0 → 선정값, 주석의 "임시" 문구 제거)
- Modify: `src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java` (v2 검증 테스트 추가)

**Interfaces:**
- Consumes: `build/reports/min-lift-sweep.md`의 표 (Task 2 산출), `GoldenSetHarness.evaluate(...)`·`VALIDATION_SEEDS`·`reportTable(...)` (Task 2).
- Produces: 확정된 `DEFAULT_MIN_LIFT`, `build/reports/golden-set-eval-v2.md` (Task 5의 기록 입력).

- [ ] **Step 1: 선정 기준 적용**

`build/reports/min-lift-sweep.md`의 표에서 **발견 ≥ 165(재현율 ≥97%)인 k 중 정밀도 최대, 동률이면 작은 k**를 고른다. 적용 과정을 리포트 파일에 한 줄씩 기록한다 (예: "k=3.0 재현율 96% → 탈락, k=2.5·2.0 통과 → 정밀도 비교 …"). 어떤 k도 발견 165 미만이면 **중단하고 BLOCKED 보고** (스펙 §4-4 실패 시나리오 — 채택하지 않고 기록만 한다).

- [ ] **Step 2: DEFAULT_MIN_LIFT 갱신**

`HighlightDetector.java`의 상수를 선정값으로 바꾸고 주석을 확정형으로:

```java
    /**
     * 절대 하한 배수 — 스무딩 값이 (전체 평균 × minLift) 이상이어야 하이라이트 후보다.
     * 골든셋 실측(2026-08-07)에서 FP 398건이 무피크·약한 신호 콘텐츠에 집중된 원인이
     * z-score의 상대성(절대 높이 하한 부재)으로 규명되어 도입 — 스펙 detector-v2-min-lift.
     * 0이면 하한 비활성 = v1(ZSCORE_V1)과 동일 동작.
     * 값은 튜닝 시드(1~10) 스윕에서 "재현율 ≥97% 유지 중 정밀도 최대" 기준으로 선정 (build/reports/min-lift-sweep.md).
     */
    public static final double DEFAULT_MIN_LIFT = /* 선정값, 예: 2.0 — Step 1 결과를 그대로 */;
```

- [ ] **Step 3: 검증 시드 실측 테스트 추가 (고정 assert는 실측 후)**

`GoldenSetEvalTest.java`에 추가:

```java
    @Test
    void 골든셋_v2_검증시드_정밀도_재현율_고정() throws Exception {
        // 공식 숫자 — 선정에 쓰지 않은 검증 시드 11~20 (스펙 detector-v2 §4-3)
        var r = GoldenSetHarness.evaluate(new HighlightDetector(), GoldenSetHarness.VALIDATION_SEEDS);

        String report = GoldenSetHarness.reportTable(r);
        System.out.println(report);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/golden-set-eval-v2.md"), report);

        assertThat(r.totalAnswers()).isEqualTo(170);

        // ── 측정값 고정 (Step 5에서 실측 후 아래 주석을 실제 assert로 교체) ──
        // assertThat(r.tp()).isEqualTo(???);
        // assertThat(r.fp()).isEqualTo(???);
        // assertThat(r.found()).isEqualTo(???);
    }
```

- [ ] **Step 4: 실행해서 v2 공식 숫자 확보**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetEvalTest"`
Expected: **PASS** (v1 고정 + v2 170 불변식). `build/reports/golden-set-eval-v2.md`의 구조별 표와 합산 줄(공식 숫자)을 리포트 파일에 복사한다.

- [ ] **Step 5: v2 실측값 고정 후 재실행**

Step 4의 실측 tp/fp/found로 주석 3줄을 실제 assert로 교체 (주석: `// 측정값 고정 (2026-08-07 실측, 검증 시드 11~20)`), 재실행:

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetEvalTest"`
Expected: **PASS** — 시드 고정이라 같은 숫자.

- [ ] **Step 6: 전체 테스트 + 커밋**

Run: `./gradlew test`
Expected: contextLoads(도커) 외 전부 green. 특히 `HighlightDetectorTest` 기존 4개가 **무수정 green**이어야 한다 (기존 케이스의 스파이크 lift ≈ 4배 > 후보 최대 3.0 — 깨지면 중단·보고).

```bash
git add src/main/java/com/scenelog/analytics/HighlightDetector.java src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java
git commit -m "feat: MIN_LIFT 확정(스윕 선정) + 검증 시드 11~20 v2 실측 고정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: ZSCORE_V2 버저닝 (쓰기 전환 + 조회 필터)

**Files:**
- Modify: `src/main/java/com/scenelog/analytics/Highlight.java` (상수 1개 추가)
- Modify: `src/main/java/com/scenelog/analytics/AggregationService.java:68-76` (V1 → V2)
- Modify: `src/main/java/com/scenelog/analytics/HighlightRepository.java` (finder에 method 파라미터)
- Modify: `src/main/java/com/scenelog/analytics/TimelineService.java:38-40` (현행 method 지정)

**Interfaces:**
- Consumes: 없음 (Task 1~3과 독립 — 검출기 변경과 무관한 버저닝 배관).
- Produces: `Highlight.METHOD_ZSCORE_V2 = "ZSCORE_V2"`, `HighlightRepository.findByContentIdAndMethodOrderByStartSec(Long, String)`.

- [ ] **Step 1: Highlight에 V2 상수 추가**

`Highlight.java`의 상수 영역:

```java
    public static final String METHOD_ZSCORE_V1 = "ZSCORE_V1";
    /** v2 = v1(z-score) + 절대 하한(minLift) — 스펙 detector-v2-min-lift. V1 행은 보존·비교용 */
    public static final String METHOD_ZSCORE_V2 = "ZSCORE_V2";
```

- [ ] **Step 2: AggregationService 쓰기 경로 V2 전환**

`AggregationService.java`의 검출·저장 블록에서 method 상수만 교체 (주석의 방식 표기도 갱신):

```java
        // 검출 — 같은 방식(ZSCORE_V2)의 이전 결과만 교체. V1 이력은 보존 (method 버저닝)
        TreeMap<Integer, Integer> totals = buckets.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().total(), (a, b) -> a, TreeMap::new));
        List<HighlightWindow> windows = detector.detect(totals, BUCKET_SIZE_SEC);

        highlightRepository.deleteAllByContentIdAndMethod(contentId, Highlight.METHOD_ZSCORE_V2);
        highlightRepository.saveAll(windows.stream()
                .map(w -> new Highlight(contentId, w, Highlight.METHOD_ZSCORE_V2))
                .toList());
```

- [ ] **Step 3: 조회에 method 필터**

`HighlightRepository.java` — 기존 `findByContentIdOrderByStartSec`를 교체:

```java
    /** 현행 방식의 결과만 조회 — 버저닝은 쓰기만이 아니라 읽기도 현행 method를 지정해야 완성된다 */
    List<Highlight> findByContentIdAndMethodOrderByStartSec(Long contentId, String method);
```

`TimelineService.java`의 highlights():

```java
    public List<HighlightResponse> highlights(Long contentId) {
        return highlightRepository.findByContentIdAndMethodOrderByStartSec(contentId, Highlight.METHOD_ZSCORE_V2)
                .stream().map(HighlightResponse::from).toList();
    }
```

(`import com.scenelog.analytics.Highlight`은 같은 패키지라 불필요. TimelineService의 캐시 애노테이션·다른 메서드는 무변경.)

- [ ] **Step 4: 다른 호출처 확인**

Run: `grep -rn "findByContentIdOrderByStartSec" src/`
Expected: 결과 0건 (교체 완료 확인). 결과가 나오면 그 호출처도 같은 방식으로 갱신하고 리포트에 기록.

- [ ] **Step 5: 단위 테스트 + (가능하면) 컨텍스트 부팅 검증**

Run: `./gradlew test`
Expected: contextLoads 외 전부 green (이 태스크의 변경은 단위 테스트 범위 밖 — 컴파일이 1차 게이트).

Docker Desktop이 가동 중이면: `docker compose up -d` 후 `./gradlew test --tests "com.scenelog.ScenelogApplicationTests"`로 파생 쿼리 이름·JPQL이 부팅 검증되는지 확인. **Docker가 비가동이면 시도만 하고 실패를 리포트에 "검증 보류"로 기록** — Docker 수리를 시도하지 마라 (오늘 이미 고장 이력 있음, 시스템 상태 변경 금지).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/scenelog/analytics/Highlight.java src/main/java/com/scenelog/analytics/AggregationService.java src/main/java/com/scenelog/analytics/HighlightRepository.java src/main/java/com/scenelog/analytics/TimelineService.java
git commit -m "feat: 하이라이트 ZSCORE_V2 버저닝 - 쓰기 전환 + 조회 method 필터 (V1 이력 보존)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: dev-log 기록

**Files:**
- Modify: `docs/dev-log.md` (맨 아래 항목 추가)

**Interfaces:**
- Consumes: `build/reports/min-lift-sweep.md` (Task 2), `build/reports/golden-set-eval-v2.md` (Task 3), 선정 과정 기록 (Task 3 리포트).

- [ ] **Step 1: dev-log 항목 작성**

`docs/dev-log.md` 맨 아래에 추가 (표·숫자는 위 산출물에서 그대로 복사, 슬롯은 실측값):

```markdown
## 2026-08-07 (7일차 추가 2) — 검출기 v2: 절대 하한(MIN_LIFT)으로 정밀도 개선

- **동기**: 골든셋 실측이 규명한 약점(정밀도 31.6%, FP 398건이 무피크·약한 신호에 집중 —
  z-score의 상대성) 처방. 브랜치 feature/detector-v2-min-lift, main·EC2·서류 무접촉.
- **변경**: 판정을 z≥2.0 AND smoothed≥평균×minLift 2단으로. minLift=0 = v1 동일(하위호환 장치).
  method 버저닝 실사용: ZSCORE_V2 쓰기 + 조회 method 필터(무필터 조회는 V1·V2 중복 반환 —
  "버저닝은 읽기도 현행 버전을 지정해야 완성된다").
- **★ 상수 선정 (튜닝 시드 1~10 스윕)**: (min-lift-sweep.md 표 전문)
  기준 "재현율 ≥97% 유지 중 정밀도 최대" 적용 과정: (Task 3 Step 1 기록) → **선정 k = (값)**
- **★ 공식 결과 (검증 시드 11~20, 한 번도 안 본 데이터)**: v1 31.6%/98.2% → **v2 (X)%/(Y)%**
  (golden-set-eval-v2.md 구조별 표 붙이기)
- **남은 한계**: 구조 8(강한 피크의 σ 부풀림이 약한 피크를 가림)은 minLift가 못 고침 —
  median/MAD 후속. (검증 시드 결과에서 실제로 남은 미검출·FP를 표에서 읽어 서술)
- **원칙 준수**: z=2.0 무변경(변수는 minLift 하나), 튜닝/검증 시드 분리로 자기 채점 방지,
  v1·v2 실측값 모두 assert 고정(전후가 테스트 코드에 영구 문서화).
- **스펙·계획**: docs/superpowers/specs/2026-08-07-detector-v2-min-lift-design.md ·
  docs/superpowers/plans/2026-08-07-detector-v2-min-lift.md
```

- [ ] **Step 2: 숫자 대조 후 커밋**

dev-log의 모든 숫자를 build/reports/ 산출물과 대조 확인 후:

```bash
git add docs/dev-log.md
git commit -m "docs: 검출기 v2 스윕 선정·검증 실측 기록 - 정밀도 개선 전후 비교

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review 결과

- 스펙 §3 4파일 변경 = Task 1(검출기)·Task 4(나머지 3파일+repository) ✓
- 스펙 §4 프로토콜 = Task 2(스윕·기준선 게이트)·Task 3(선정 기준 기계 적용·검증 시드·실패 시 BLOCKED) ✓
- 스펙 §5 테스트 전략 = Task 1(TDD 2케이스·기존 4개 무수정), Task 2(리팩터 동작 보존 게이트), Task 3(양버전 고정) ✓
- 스펙 §6 기록 = Task 5 ✓ · 스펙 §2 제외(서류·EC2·yml 설정화) = 어느 태스크에도 없음 ✓
- 타입 일관성: `EvalResult(tp, fp, found, missed, byStructure)` 접근자 사용처(Task 2 스윕·Task 3 v2 테스트) 일치, `HighlightDetector(double)` 시그니처 Task 1 정의 = Task 2·3 사용 ✓
- 실측 슬롯(Task 3 선정값·고정값, Task 5 숫자)은 전부 "어느 산출물에서 복사"가 명시된 측정 고유 슬롯 ✓
- Task 4 Step 5의 Docker 지침: 수리 금지 명시 (오늘 사고 재발 방지) ✓
