# 골든셋 다양화 정밀도·재현율 측정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 구조 12종 × 시드 10 = 120회 합성 골든셋으로 하이라이트 검출기의 정밀도·재현율을 측정하고, 실측값을 회귀 방지 assert로 고정한다.

**Architecture:** 운영과 동일한 순수 클래스 경로(`ReactionSimulator` → `Aggregator` → `HighlightDetector`)를 JUnit 테스트에서 직접 호출한다. 채점기(`GoldenSetScorer`)를 테스트 소스에 별도 클래스로 두고 TDD로 만들고, 평가 러너(`GoldenSetEvalTest`)가 120회를 돌려 콘솔 + `build/reports/golden-set-eval.md`에 결과표를 쓴다.

**Tech Stack:** Java 21, Spring Boot 프로젝트의 Gradle 테스트 (JUnit 5, AssertJ). Spring 컨텍스트·DB 불필요.

**스펙:** `docs/superpowers/specs/2026-08-07-golden-set-precision-design.md`

## Global Constraints

- **운영 코드 수정 0줄** — `src/main/**`는 어떤 파일도 건드리지 않는다.
- **임계값 z=2.0 유지** — 결과가 나빠도 `HighlightDetector` 상수를 바꾸지 않는다. 나쁜 숫자는 그대로 기록.
- **EC2 무접촉** — 서버 동결. 모든 작업은 로컬 메모리 연산.
- 커밋 메시지는 한국어, `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` 푸터.
- 모든 명령은 `C:\Users\aucu2\Project\movieprojcet\scenelog`에서 실행 (Git Bash 기준 `/c/Users/aucu2/Project/movieprojcet/scenelog`).
- 정답 피크 총수는 **170** (구조당 합계 17 × 시드 10) — 어긋나면 시나리오 정의가 스펙과 다른 것.

---

### Task 1: 채점기 GoldenSetScorer (TDD)

**Files:**
- Create: `src/test/java/com/scenelog/analytics/GoldenSetScorer.java`
- Test: `src/test/java/com/scenelog/analytics/GoldenSetScorerTest.java`

**Interfaces:**
- Consumes: `HighlightWindow(int startSec, int endSec, double score)` (운영 record), `Peak(int startSec, int endSec, ReactionType type, double multiplier)` (운영 record)
- Produces: `GoldenSetScorer.Score(int tpDetections, int fp, int foundPeaks, int missedPeaks)` record와 `static Score score(List<HighlightWindow> detected, List<Peak> answers)` — Task 2가 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 스텁 + 실패하는 테스트 작성**

`GoldenSetScorer.java` — 스텁 (항상 0을 반환):

```java
package com.scenelog.analytics;

import com.scenelog.reaction.sim.Peak;

import java.util.List;

/**
 * 골든셋 채점기 (스펙 §4): 검출 구간과 정답 피크를 겹침(1초 이상)으로 매칭한다.
 *
 * <p>비대칭 규칙: 병합 검출 하나가 정답 두 개를 덮으면 정밀도 쪽 TP는 1, 재현율 쪽 발견은 2다.
 * 사용자 입장에서 "그 구간이 하이라이트"라는 답은 맞으므로 병합에 벌점을 주지 않는다.
 */
final class GoldenSetScorer {

    /** 한 실행의 채점 결과. tpDetections/fp는 검출 기준, foundPeaks/missedPeaks는 정답 기준. */
    record Score(int tpDetections, int fp, int foundPeaks, int missedPeaks) {}

    private GoldenSetScorer() {}

    static Score score(List<HighlightWindow> detected, List<Peak> answers) {
        return new Score(0, 0, 0, 0);   // 스텁 — Step 3에서 구현
    }
}
```

`GoldenSetScorerTest.java`:

```java
package com.scenelog.analytics;

import com.scenelog.reaction.ReactionType;
import com.scenelog.reaction.sim.Peak;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.scenelog.analytics.GoldenSetScorer.Score;
import static com.scenelog.analytics.GoldenSetScorer.score;
import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetScorerTest {

    private static Peak peak(int start, int end) {
        return new Peak(start, end, ReactionType.TENSION, 15.0);
    }

    @Test
    void 겹치는_검출은_TP이고_그_정답은_발견이다() {
        var detected = List.of(new HighlightWindow(100, 130, 3.0));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(1, 0, 1, 0));
    }

    @Test
    void 어떤_정답과도_안_겹치는_검출은_FP이고_그_정답은_FN이다() {
        var detected = List.of(new HighlightWindow(500, 520, 2.5));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(0, 1, 0, 1));
    }

    @Test
    void 경계가_맞닿기만_하면_겹침이_아니다() {   // [100,120) vs [120,170) — 공유 구간 0초
        var detected = List.of(new HighlightWindow(100, 120, 2.5));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(0, 1, 0, 1));
    }

    @Test
    void 병합_검출_하나가_정답_둘을_덮으면_TP1_발견2다() {   // 스펙 §4 비대칭 규칙
        var detected = List.of(new HighlightWindow(3000, 3130, 4.0));
        var answers = List.of(peak(3000, 3050), peak(3080, 3130));
        assertThat(score(detected, answers)).isEqualTo(new Score(1, 0, 2, 0));
    }

    @Test
    void 무피크_콘텐츠에서_검출이_없으면_전부_0이다() {
        assertThat(score(List.of(), List.of())).isEqualTo(new Score(0, 0, 0, 0));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetScorerTest"`
Expected: **FAIL** — 테스트 5개 중 4개 실패 (`무피크_콘텐츠에서_검출이_없으면_전부_0이다`만 스텁과 우연히 일치해 통과 — 정상).

- [ ] **Step 3: 채점 로직 구현**

`GoldenSetScorer.java`의 `score`를 교체:

```java
    /** [start, end) 반개구간끼리 1초 이상 공유하면 겹침 */
    private static boolean overlaps(HighlightWindow w, Peak p) {
        return w.startSec() < p.endSec() && p.startSec() < w.endSec();
    }

    static Score score(List<HighlightWindow> detected, List<Peak> answers) {
        int tp = 0, fp = 0;
        for (HighlightWindow w : detected) {
            if (answers.stream().anyMatch(a -> overlaps(w, a))) tp++;
            else fp++;
        }
        int found = 0, missed = 0;
        for (Peak a : answers) {
            if (detected.stream().anyMatch(w -> overlaps(w, a))) found++;
            else missed++;
        }
        return new Score(tp, fp, found, missed);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetScorerTest"`
Expected: **PASS** — 5개 전부 green.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/scenelog/analytics/GoldenSetScorer.java src/test/java/com/scenelog/analytics/GoldenSetScorerTest.java
git commit -m "test: 골든셋 채점기 - 겹침 매칭·병합 비대칭 규칙 (TDD)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 골든셋 12종 정의 + 120회 평가 러너

**Files:**
- Create: `src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java`

**Interfaces:**
- Consumes: `GoldenSetScorer.score(List<HighlightWindow>, List<Peak>)` → `Score(tpDetections, fp, foundPeaks, missedPeaks)` (Task 1), 운영 클래스 `ReactionSimulator.generate(Scenario, int users, long seed)`, `Aggregator.aggregate(List<ReactionEvent>, int bucketSizeSec)` → `Map<Integer, BucketCounts>`, `HighlightDetector.detect(SortedMap<Integer,Integer>, int)` → `List<HighlightWindow>`, `Scenario(long contentId, int durationSec, double baselinePerMinute, List<Peak> peaks)`
- Produces: 리포트 파일 `build/reports/golden-set-eval.md` (Task 3이 읽음), 콘솔에 동일 내용 출력.

- [ ] **Step 1: 평가 러너 작성**

`GoldenSetEvalTest.java` 전체:

```java
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
```

- [ ] **Step 2: 실행해서 결과표 확보**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetEvalTest"`
Expected: **PASS** (고정 assert는 아직 주석). 이어서 `build/reports/golden-set-eval.md`를 읽어 구조별 표와 "합산: TP(검출)=…, FP=…, 발견=…/170 — 정밀도 …%, 재현율 …%" 줄을 확보한다. 실행 시간이 1분을 넘으면 비정상 (전부 메모리 연산 — 수 초가 정상).

- [ ] **Step 3: 커밋**

```bash
git add src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java
git commit -m "test: 골든셋 12종 x 시드 10 = 120회 평가 러너 - 정밀도·재현율 측정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 측정값 고정 + 전체 테스트 + dev-log 기록

**Files:**
- Modify: `src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java` (측정값 고정 주석 3줄 → 실제 assert)
- Modify: `docs/dev-log.md` (맨 아래에 항목 추가)

**Interfaces:**
- Consumes: Task 2가 만든 `build/reports/golden-set-eval.md`의 실측값 (TP, FP, 발견 수, 구조별 표).

- [ ] **Step 1: 실측값을 assert로 고정**

`GoldenSetEvalTest.java` 끝부분의 주석 3줄을 Task 2 Step 2에서 확보한 실제 숫자로 교체한다. 예시 형태 (숫자는 반드시 실측값):

```java
        // 측정값 고정 (2026-08-07 실측) — 검출기 상수를 바꾸면 여기가 깨진다 (회귀 방지)
        assertThat(tp).isEqualTo(/* 실측 TP */);
        assertThat(fp).isEqualTo(/* 실측 FP */);
        assertThat(found).isEqualTo(/* 실측 발견 수 */);
```

- [ ] **Step 2: 평가 테스트 재실행으로 고정값 검증**

Run: `./gradlew test --tests "com.scenelog.analytics.GoldenSetEvalTest"`
Expected: **PASS** — 시드 고정이라 같은 숫자가 다시 나온다. 실패하면 숫자를 잘못 옮긴 것.

- [ ] **Step 3: 전체 테스트 green 확인**

Run: `./gradlew test`
Expected: **BUILD SUCCESSFUL** — 기존 34개 + 신규 6개(채점기 5 + 러너 1) = 40개 전부 통과. 개수는 출력에서 확인해 dev-log에 그대로 적는다.

- [ ] **Step 4: dev-log 기록**

`docs/dev-log.md` 맨 아래에 추가 (구조별 표와 숫자는 `build/reports/golden-set-eval.md`에서 그대로 복사):

```markdown
## 2026-08-07 (7일차 추가) — 골든셋 다양화: 검출 정밀도·재현율 실측

- **동기**: 기존 정확도 근거는 기본 각본 1종의 2/2 — n이 작고, 무피크 콘텐츠가 없어 허위 검출을 잰 적이 없음.
- **방법**: 구조 12종(무피크 2·약한·가장자리·인접·강약혼합·고노이즈·완만 포함) × 시드 10 = 120회,
  정답 피크 170개. 운영과 동일 클래스 경로(Simulator→Aggregator→Detector)를 순수 JUnit으로.
  채점은 겹침 매칭(병합 무벌점) — GoldenSetScorer TDD 5케이스.
- **★ 결과**: (build/reports/golden-set-eval.md의 구조별 표 + 합산 줄을 여기 붙인다)
- **발견**: (표에서 읽히는 검출기 성질 2~3줄 — 예: 어떤 구조에서 놓쳤나, FP는 어디서 났나,
  인접 피크 병합 여부, 강한 피크의 σ 부풀림이 약한 피크를 가렸는가)
- **원칙**: z=2.0 유지 — 측정이 튜닝이 되지 않도록 임계값은 건드리지 않고 한계로 기록.
  실측값은 assert로 고정 → 이후 상수 변경 시 회귀 테스트가 잡는다.
- **스펙·계획**: docs/superpowers/specs/2026-08-07-golden-set-precision-design.md ·
  docs/superpowers/plans/2026-08-07-golden-set-precision-eval.md
```

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/scenelog/analytics/GoldenSetEvalTest.java docs/dev-log.md
git commit -m "test: 골든셋 실측값 고정(회귀 방지) + dev-log 정밀도·재현율 기록

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 서류용 숫자 문장 초안 (저장소 밖 — 커밋 없음)

**Files:**
- Modify: `C:\Users\aucu2\Project\movieprojcet\이력서-숫자문장-초안.md` (저장소 밖 — §12 원칙에 따라 git에 넣지 않는다)

**Interfaces:**
- Consumes: Task 3의 dev-log에 기록된 합산 정밀도·재현율과 구조 요약.

- [ ] **Step 1: 문장 초안 추가**

파일을 먼저 읽고 기존 문장들의 형식(문장 + 면접 뒷받침 구조)을 따라 맨 아래에 추가한다. 형태 (X·Y·FP·발견 수는 실측값):

```markdown
## 5. 검출 정밀도·재현율 (2026-08-07 골든셋 다양화)

**문장**: 정답 피크를 심은 합성 골든셋 120건(무피크·약한 신호·인접 피크 등 구조 12종 × 시드 10,
정답 피크 170개)으로 하이라이트 검출기의 정밀도 X%·재현율 Y%를 실측했습니다.

**면접 뒷받침**:
- 왜 했나 — 기존 2/2(100%)는 n이 작고 허위 검출을 잰 적이 없어서, 평가셋을 직접 다양화함.
- 임계값 근거 — z≥2.0은 통계 관례(2σ)의 시작값. 이 측정으로 유지 근거가 실측이 됨.
- 정직한 한계 — 약한 피크(4배)·완만한 피크(3배)는 설계상 놓치라고 넣은 스트레스 케이스.
  (실제 결과에 맞춰 서술: 어떤 구조에서 몇 개를 놓쳤는지 1줄)
- 재현 가능 — 시드 고정 120회, 실측값을 assert로 고정해 회귀 방지 테스트로 남김.
```

- [ ] **Step 2: 사용자에게 보고**

측정 숫자(정밀도·재현율·FP·놓친 구조)와 초안 위치를 요약해 보고한다. 서류 반영은 사용자가 직접 한다 — 커밋·push·EC2 접근은 하지 않는다.

---

## Self-Review 결과

- 스펙 §2 "제외" 항목 전부 준수: 운영 코드 0줄(테스트 소스만 생성), 임계값 유지, EC2 무접촉 ✓
- 스펙 §3 표의 12구조 파라미터를 코드에 그대로 옮김 (6번 구조 1944~1994·4896~4956 = 기존 defaultFor 27%·68% 등가) ✓
- 스펙 §4 채점 규칙 = GoldenSetScorer 테스트 5케이스 (겹침·FP·경계 맞닿음·병합 비대칭·무피크) ✓
- 스펙 §5 "측정값 고정 assert" = Task 3 Step 1, "채점 함수 자체 테스트" = Task 1 ✓
- 스펙 §6 기록 원칙 = Task 3 Step 4의 dev-log 템플릿 ✓
- 타입 일관성: `Score(tpDetections, fp, foundPeaks, missedPeaks)` 시그니처가 Task 1 정의·Task 2 사용처 일치 ✓
- 실측값이 필요한 자리(Task 3·4)는 "Task 2 산출물에서 복사"로 출처를 명시 — 측정 작업의 본질상 사전 기입 불가능한 유일한 슬롯 ✓
