# SceneLog 핵심 5 파일 구현 계획 (Core Five Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로젝트의 심장부인 순수 로직 5개 — ETL 검증기·정제기, 반응 시뮬레이터(각본), 집계기, 하이라이트 검출기 — 를 TDD로 구현한다. 이 5개가 §11 정량 성과 3개 중 2개(품질 검출 수치, 검출 정확도)를 만들어낸다.

**Architecture:** 전부 **DB 없이 테스트 가능한 순수 로직**이다 (기획서 §9의 JSONB+H2 판단). Controller/Repository/Mongo 연동은 이 계획의 범위 밖 — 각 날짜(8/1~8/3)에 이 로직을 감싸는 껍데기로 추가된다. Circuit Breaker(§3)가 발동해 저장소가 바뀌어도 이 5개는 영향을 받지 않는다.

**Tech Stack:** Java 17 · Spring Boot 4.1.0 (로직은 Spring 비의존) · JUnit 5 + AssertJ · Gradle

## Global Constraints

- 루트 패키지 `com.scenelog` (기획서 §2-A)
- 이 계획의 클래스는 **Spring 어노테이션 없이** 작성한다 (`@Component` 부착은 껍데기 작업일에). 테스트에서 `new`로 생성 가능해야 한다.
- 테스트는 DB·네트워크·시계에 의존하지 않는다. 시간은 `Clock` 주입, 난수는 `seed` 주입.
- 커밋은 task마다 1회. 메시지는 `feat|test: <요약>` 형식.
- 실행 명령은 저장소 루트(`scenelog/`)에서 PowerShell 기준: `.\gradlew.bat test --tests "<FQCN>" --console=plain`
- 상수는 코드에 박되 `static final`로 이름을 붙인다. 값 변경은 상수 한 줄 수정으로 끝나야 한다.

## 공유 타입 (Task 간 인터페이스 요약)

| 타입 | 정의 위치 | 사용처 |
|---|---|---|
| `ContentType` (enum MOVIE, OTT) | Task 1 | Task 2 |
| `TmdbItem` (record) | Task 1 | Task 1, 2 |
| `ValidationResult` (record) | Task 1 | Task 1 |
| `TmdbNormalized` (record) | Task 2 | Task 2 |
| `ReactionType` (enum) | Task 3 | Task 3, 4 |
| `ReactionEvent` (record) | Task 3 | Task 3, 4 |
| `Peak`, `Scenario` (record) | Task 3 | Task 3, 5(테스트) |
| `BucketCounts` (record) | Task 4 | Task 4, 5 |
| `HighlightWindow` (record) | Task 5 | Task 5 |

---

### Task 1: ContentValidator — ETL 검증 (누락·중복·정합성)

**Files:**
- ~~Create: `src/main/java/com/scenelog/content/ContentType.java`~~ → **7/31 auth·CRUD 작업에서 이미 생성됨** (Content 엔티티가 필요로 함). Step 3에서 다시 만들지 말 것.
- Create: `src/main/java/com/scenelog/etl/TmdbItem.java`
- Create: `src/main/java/com/scenelog/etl/ValidationResult.java`
- Create: `src/main/java/com/scenelog/etl/ContentValidator.java`
- Test: `src/test/java/com/scenelog/etl/ContentValidatorTest.java`

**Interfaces:**
- Consumes: 없음 (최초 task)
- Produces: `ValidationResult validate(TmdbItem item, Set<Integer> existingTmdbIds)` — Task 이후 `EtlService`(8/1)가 호출. `Status.OK`→적재, `DUPLICATE`→UPSERT, `REJECTED`→`rejected_records` 격리.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.scenelog.etl;

import com.scenelog.etl.ValidationResult.Status;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContentValidatorTest {

    // 시계 고정: 2026-07-30 — "미래 개봉일" 판정이 오늘 날짜에 흔들리지 않게 한다
    private final Clock fixed = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);
    private final ContentValidator validator = new ContentValidator(fixed);

    private TmdbItem movie(Integer id, String title, Integer runtime, String releaseDate) {
        return new TmdbItem(id, title, null, "Original", runtime, releaseDate, null, "movie");
    }

    @Test
    void 정상_영화는_OK다() {
        ValidationResult r = validator.validate(movie(27205, "인셉션", 148, "2010-07-15"), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void id가_없으면_REJECTED_MISSING_FIELD() {
        ValidationResult r = validator.validate(movie(null, "인셉션", 148, "2010-07-15"), Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("MISSING_FIELD:id");
    }

    @Test
    void 제목이_전부_비면_REJECTED다() {
        TmdbItem noTitle = new TmdbItem(1, "", null, "", 100, "2020-01-01", null, "movie");
        ValidationResult r = validator.validate(noTitle, Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("MISSING_FIELD:title");
    }

    @Test
    void 이미_적재된_tmdb_id면_DUPLICATE다() {
        ValidationResult r = validator.validate(movie(27205, "인셉션", 148, "2010-07-15"), Set.of(27205));
        assertThat(r.status()).isEqualTo(Status.DUPLICATE);
    }

    @Test
    void runtime이_null이면_경고와_함께_OK다() {  // TMDB에서 흔한 실제 케이스 (§5.3)
        ValidationResult r = validator.validate(movie(2, "짧은 영화", null, "2020-01-01"), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).containsExactly("MISSING_FIELD:runtime");
    }

    @Test
    void runtime이_0이하이거나_600분_초과면_REJECTED_INTEGRITY() {
        assertThat(validator.validate(movie(3, "A", -5, "2020-01-01"), Set.of()).rejectReason())
                .isEqualTo("INTEGRITY_FAIL:runtime");
        assertThat(validator.validate(movie(4, "B", 601, "2020-01-01"), Set.of()).rejectReason())
                .isEqualTo("INTEGRITY_FAIL:runtime");
    }

    @Test
    void 개봉일이_미래면_REJECTED_INTEGRITY() {
        ValidationResult r = validator.validate(movie(5, "미래영화", 100, "2027-01-01"), Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("INTEGRITY_FAIL:release_date");
    }

    @Test
    void 날짜가_빈문자열이면_경고와_함께_OK다() {  // TMDB 실제 케이스: "" (§5.3)
        ValidationResult r = validator.validate(movie(6, "C", 100, ""), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).containsExactly("MISSING_FIELD:release_date");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.etl.ContentValidatorTest" --console=plain`
Expected: **컴파일 실패** — `TmdbItem`, `ValidationResult`, `ContentValidator` 미존재

- [ ] **Step 3: 최소 구현**

`ContentType.java`:
```java
package com.scenelog.content;

/** TRAILER는 TMDB 매핑 불가로 제외 (기획서 §5.3) */
public enum ContentType { MOVIE, OTT }
```

`TmdbItem.java`:
```java
package com.scenelog.etl;

/**
 * TMDB 응답에서 검증·정제에 필요한 필드만 추린 중간 표현.
 * movie는 title/releaseDate, tv는 name/firstAirDate를 쓴다 — 선택 로직은 소비자 몫.
 */
public record TmdbItem(
        Integer id,
        String title,          // movie
        String name,           // tv
        String originalTitle,
        Integer runtime,       // 분 단위. null 흔함 (§5.3)
        String releaseDate,    // movie. "" 가능
        String firstAirDate,   // tv
        String mediaType       // "movie" | "tv"
) {
    /** 표시용 제목: 매체별 필드 → 원제 순서로 폴백 */
    public String displayTitle() {
        String primary = "tv".equals(mediaType) ? name : title;
        if (primary != null && !primary.isBlank()) return primary;
        return originalTitle;
    }

    /** 매체별 날짜 필드 선택 */
    public String displayDate() {
        return "tv".equals(mediaType) ? firstAirDate : releaseDate;
    }
}
```

`ValidationResult.java`:
```java
package com.scenelog.etl;

import java.util.List;

public record ValidationResult(Status status, String rejectReason, List<String> warnings) {

    public enum Status { OK, DUPLICATE, REJECTED }

    public static ValidationResult ok(List<String> warnings) {
        return new ValidationResult(Status.OK, null, List.copyOf(warnings));
    }

    public static ValidationResult duplicate() {
        return new ValidationResult(Status.DUPLICATE, null, List.of());
    }

    public static ValidationResult rejected(String reason) {
        return new ValidationResult(Status.REJECTED, reason, List.of());
    }
}
```

`ContentValidator.java`:
```java
package com.scenelog.etl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 적재 전 3종 검사: 누락 · 중복 · 정합성 (기획서 §5-A-2).
 * REJECTED는 rejected_records 격리 대상, DUPLICATE는 UPSERT 경로.
 */
public class ContentValidator {

    static final int MAX_RUNTIME_MINUTES = 600;

    private final Clock clock;

    public ContentValidator(Clock clock) {
        this.clock = clock;
    }

    public ValidationResult validate(TmdbItem item, Set<Integer> existingTmdbIds) {
        // 1) 누락 — 적재 불가 필드
        if (item.id() == null) return ValidationResult.rejected("MISSING_FIELD:id");
        String title = item.displayTitle();
        if (title == null || title.isBlank()) return ValidationResult.rejected("MISSING_FIELD:title");

        // 2) 정합성
        Integer runtime = item.runtime();
        if (runtime != null && (runtime <= 0 || runtime > MAX_RUNTIME_MINUTES)) {
            return ValidationResult.rejected("INTEGRITY_FAIL:runtime");
        }
        String rawDate = item.displayDate();
        LocalDate date = null;
        boolean dateMissing = (rawDate == null || rawDate.isBlank());
        if (!dateMissing) {
            try {
                date = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ValidationResult.rejected("INTEGRITY_FAIL:release_date");
            }
            if (date.isAfter(LocalDate.now(clock))) {
                return ValidationResult.rejected("INTEGRITY_FAIL:release_date");
            }
        }

        // 3) 중복 — UPSERT 경로로 보낸다 (버리지 않는다)
        if (existingTmdbIds.contains(item.id())) return ValidationResult.duplicate();

        // 4) 누락이지만 적재 가능한 필드 → 경고로 기록 (quality_reports 반영용)
        List<String> warnings = new ArrayList<>();
        if (runtime == null) warnings.add("MISSING_FIELD:runtime");
        if (dateMissing) warnings.add("MISSING_FIELD:release_date");
        return ValidationResult.ok(warnings);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.etl.ContentValidatorTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 8 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/scenelog/content/ContentType.java src/main/java/com/scenelog/etl/ src/test/java/com/scenelog/etl/ContentValidatorTest.java
git commit -m "feat: ETL 검증기 - 누락·중복·정합성 3종 검사 (TDD)"
```

---

### Task 2: ContentTransformer — TMDB → 내부 형식 정제

**Files:**
- Create: `src/main/java/com/scenelog/etl/TmdbNormalized.java`
- Create: `src/main/java/com/scenelog/etl/ContentTransformer.java`
- Test: `src/test/java/com/scenelog/etl/ContentTransformerTest.java`

**Interfaces:**
- Consumes: `TmdbItem` (Task 1), `ContentType` (Task 1)
- Produces: `TmdbNormalized transform(TmdbItem item)` — `EtlService`(8/1)가 검증 통과분에 호출 후 `Content` 엔티티로 매핑. **검증을 통과한 item만 들어온다고 가정한다** (id·title 존재 보장).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.scenelog.etl;

import com.scenelog.content.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTransformerTest {

    private final ContentTransformer transformer = new ContentTransformer();

    @Test
    void 영화_runtime은_분에서_초로_변환된다() {  // §5.3: 148분 → 8880초
        TmdbItem item = new TmdbItem(27205, "인셉션", null, "Inception", 148, "2010-07-15", null, "movie");
        TmdbNormalized n = transformer.transform(item);
        assertThat(n.tmdbId()).isEqualTo(27205);
        assertThat(n.title()).isEqualTo("인셉션");
        assertThat(n.contentType()).isEqualTo(ContentType.MOVIE);
        assertThat(n.durationSec()).isEqualTo(8880);
        assertThat(n.releaseDate()).isEqualTo(LocalDate.of(2010, 7, 15));
    }

    @Test
    void runtime_null은_null로_유지된다() {  // 검출기가 실측 최대 offset을 상한으로 쓴다 (§5.3)
        TmdbItem item = new TmdbItem(2, "A", null, "A", null, "2020-01-01", null, "movie");
        assertThat(transformer.transform(item).durationSec()).isNull();
    }

    @Test
    void tv는_OTT타입_name필드_firstAirDate를_쓴다() {
        TmdbItem tv = new TmdbItem(1399, null, "왕좌의 게임", "Game of Thrones", 57, null, "2011-04-17", "tv");
        TmdbNormalized n = transformer.transform(tv);
        assertThat(n.contentType()).isEqualTo(ContentType.OTT);
        assertThat(n.title()).isEqualTo("왕좌의 게임");
        assertThat(n.releaseDate()).isEqualTo(LocalDate.of(2011, 4, 17));
    }

    @Test
    void 한국어_제목이_비면_원제로_폴백한다() {  // §5-A-1: language=ko-KR 결손 대응
        TmdbItem item = new TmdbItem(3, "", null, "Obscure Film", 90, "2019-05-05", null, "movie");
        assertThat(transformer.transform(item).title()).isEqualTo("Obscure Film");
    }

    @Test
    void 빈문자열_날짜는_null이_된다() {  // §5.3: TMDB는 ""를 보낸다
        TmdbItem item = new TmdbItem(4, "B", null, "B", 90, "", null, "movie");
        assertThat(transformer.transform(item).releaseDate()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.etl.ContentTransformerTest" --console=plain`
Expected: 컴파일 실패 — `TmdbNormalized`, `ContentTransformer` 미존재

- [ ] **Step 3: 최소 구현**

`TmdbNormalized.java`:
```java
package com.scenelog.etl;

import com.scenelog.content.ContentType;

import java.time.LocalDate;

/** 정제 완료 형식 — Content 엔티티(8/1)로 1:1 매핑된다. durationSec은 null 허용 (§5.3) */
public record TmdbNormalized(
        int tmdbId,
        String title,
        ContentType contentType,
        Integer durationSec,
        LocalDate releaseDate
) {}
```

`ContentTransformer.java`:
```java
package com.scenelog.etl;

import com.scenelog.content.ContentType;

import java.time.LocalDate;

/**
 * TMDB 형식 → 내부 형식 (기획서 §5.3 정제 규칙표의 구현).
 * 전제: ContentValidator를 통과한 item만 받는다 (id·displayTitle 존재).
 */
public class ContentTransformer {

    public TmdbNormalized transform(TmdbItem item) {
        ContentType type = "tv".equals(item.mediaType()) ? ContentType.OTT : ContentType.MOVIE;
        Integer durationSec = item.runtime() == null ? null : item.runtime() * 60;
        String rawDate = item.displayDate();
        LocalDate date = (rawDate == null || rawDate.isBlank()) ? null : LocalDate.parse(rawDate);
        return new TmdbNormalized(item.id(), item.displayTitle(), type, durationSec, date);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.etl.ContentTransformerTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/scenelog/etl/TmdbNormalized.java src/main/java/com/scenelog/etl/ContentTransformer.java src/test/java/com/scenelog/etl/ContentTransformerTest.java
git commit -m "feat: ETL 정제기 - 분→초 변환, tv/movie 필드 선택, 원제 폴백 (TDD)"
```

---

### Task 3: Scenario + ReactionSimulator — 각본 기반 반응 생성 (정답지)

**Files:**
- Create: `src/main/java/com/scenelog/reaction/ReactionType.java`
- Create: `src/main/java/com/scenelog/reaction/ReactionEvent.java`
- Create: `src/main/java/com/scenelog/reaction/sim/Peak.java`
- Create: `src/main/java/com/scenelog/reaction/sim/Scenario.java`
- Create: `src/main/java/com/scenelog/reaction/sim/ReactionSimulator.java`
- Test: `src/test/java/com/scenelog/reaction/sim/ReactionSimulatorTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `List<ReactionEvent> generate(Scenario scenario, int userCount, long seed)` — 시연 모드(8/2 `SimulateController`)와 시드 모드(`SeedRunner`)가 공용 호출. `seed` 고정 시 결과 결정적.
  - `Scenario.peaks()` — Task 5의 검출 정확도 테스트가 **정답지**로 재사용 (§5-B-5)
  - `ReactionEvent`는 아직 순수 record다. Mongo `@Document` 부착은 8/2 저장 연동 때 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactionSimulatorTest {

    private final ReactionSimulator simulator = new ReactionSimulator();

    // 인셉션 각본: 2400~2450 TENSION, 6100~6160 TOUCHED (기획서 §5-B-1)
    private final Scenario inception = new Scenario(1L, 8880, 0.5, List.of(
            new Peak(2400, 2450, ReactionType.TENSION, 15.0),
            new Peak(6100, 6160, ReactionType.TOUCHED, 12.0)
    ));

    @Test
    void 모든_이벤트의_offset은_상영시간_안에_있다() {
        List<ReactionEvent> events = simulator.generate(inception, 20, 42L);
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.offsetSec()).isBetween(0, 8879);
            assertThat(e.contentId()).isEqualTo(1L);
        });
    }

    @Test
    void 같은_seed는_같은_결과를_낸다() {  // 결정적이어야 테스트와 시연이 재현 가능하다
        List<ReactionEvent> a = simulator.generate(inception, 20, 42L);
        List<ReactionEvent> b = simulator.generate(inception, 20, 42L);
        assertThat(a).hasSameSizeAs(b);
        assertThat(a.get(0).offsetSec()).isEqualTo(b.get(0).offsetSec());
    }

    @Test
    void 피크_구간의_초당_반응밀도는_바깥의_3배를_넘는다() {  // 노이즈 위에 정답이 실제로 솟아있는지
        List<ReactionEvent> events = simulator.generate(inception, 50, 42L);
        long inPeak = events.stream()
                .filter(e -> e.offsetSec() >= 2400 && e.offsetSec() < 2450).count();
        long outside = events.stream()
                .filter(e -> e.offsetSec() >= 3000 && e.offsetSec() < 3050).count();
        double inDensity = inPeak / 50.0;
        double outDensity = Math.max(outside, 1) / 50.0;
        assertThat(inDensity / outDensity).isGreaterThan(3.0);
    }

    @Test
    void client_event_id는_전부_유일하다() {  // 멱등키의 전제 (§5.2)
        List<ReactionEvent> events = simulator.generate(inception, 20, 42L);
        long distinct = events.stream().map(ReactionEvent::clientEventId).distinct().count();
        assertThat(distinct).isEqualTo(events.size());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.reaction.sim.ReactionSimulatorTest" --console=plain`
Expected: 컴파일 실패 — reaction 패키지 타입 전부 미존재

- [ ] **Step 3: 최소 구현**

`ReactionType.java`:
```java
package com.scenelog.reaction;

public enum ReactionType { LAUGH, TENSION, TOUCHED, BORED }
```

`ReactionEvent.java`:
```java
package com.scenelog.reaction;

import java.time.Instant;

/**
 * 반응 이벤트 원본 (기획서 §5.2 reaction_events).
 * 지금은 순수 record — Mongo @Document 매핑은 8/2 저장 연동에서 부착한다.
 */
public record ReactionEvent(
        String clientEventId,   // 멱등키 (unique)
        long sessionId,
        long contentId,
        long userId,
        int offsetSec,          // 재생 시작 기준 경과 초
        ReactionType reactionType,
        Instant createdAt
) {}
```

`Peak.java`:
```java
package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionType;

/** 각본에 심는 정답 피크: [startSec, endSec) 구간에서 발생 확률이 multiplier배가 된다 */
public record Peak(int startSec, int endSec, ReactionType type, double multiplier) {

    public boolean contains(int sec) {
        return sec >= startSec && sec < endSec;
    }
}
```

`Scenario.java`:
```java
package com.scenelog.reaction.sim;

import java.util.List;

/**
 * 시뮬레이터 각본 = 정답지 (기획서 §5-B-1).
 * peaks가 곧 하이라이트 검출의 기대 정답이며, 검출 정확도 테스트가 재사용한다.
 */
public record Scenario(
        long contentId,
        int durationSec,
        double baselinePerMinute,   // 배경 노이즈: 사용자당 분당 반응 횟수
        List<Peak> peaks
) {}
```

`ReactionSimulator.java`:
```java
package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 각본대로 반응 이벤트를 생성한다 (기획서 §5-B-2).
 * 순수 생성 로직 — REST 전송(시연 모드)과 벌크 적재(시드 모드)는 각자 이 결과를 소비한다.
 */
public class ReactionSimulator {

    /** 피크 구간 안에서 피크 타입이 아닌 다른 반응이 섞이는 비율 (현실성) */
    static final double OFF_TYPE_RATIO = 0.2;

    public List<ReactionEvent> generate(Scenario scenario, int userCount, long seed) {
        Random rnd = new Random(seed);
        List<ReactionEvent> events = new ArrayList<>();
        ReactionType[] types = ReactionType.values();

        for (int u = 1; u <= userCount; u++) {
            long sessionId = u;   // 순수 생성 단계의 합성 ID — 시연 모드가 실제 세션 ID로 치환
            int seq = 0;
            for (int sec = 0; sec < scenario.durationSec(); sec++) {
                double p = scenario.baselinePerMinute() / 60.0;
                Peak active = null;
                for (Peak peak : scenario.peaks()) {
                    if (peak.contains(sec)) { active = peak; break; }
                }
                if (active != null) p *= active.multiplier();

                if (rnd.nextDouble() >= p) continue;

                ReactionType type;
                if (active != null && rnd.nextDouble() >= OFF_TYPE_RATIO) {
                    type = active.type();
                } else {
                    type = types[rnd.nextInt(types.length)];
                }
                events.add(new ReactionEvent(
                        "sim-%d-%d".formatted(sessionId, seq++),
                        sessionId, scenario.contentId(), u, sec, type, Instant.EPOCH));
            }
        }
        return events;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.reaction.sim.ReactionSimulatorTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/scenelog/reaction/ src/test/java/com/scenelog/reaction/
git commit -m "feat: 반응 시뮬레이터 - 각본(정답지) 기반 결정적 생성 (TDD)"
```

---

### Task 4: Aggregator — 10초 버킷 집계 (멱등)

**Files:**
- Create: `src/main/java/com/scenelog/analytics/BucketCounts.java`
- Create: `src/main/java/com/scenelog/analytics/Aggregator.java`
- Test: `src/test/java/com/scenelog/analytics/AggregatorTest.java`

**Interfaces:**
- Consumes: `ReactionEvent`, `ReactionType` (Task 3)
- Produces: `Map<Integer, BucketCounts> aggregate(Collection<ReactionEvent> events, int bucketSizeSec)` — 8/3의 집계 서비스가 이 결과를 `segment_stats`에 UPSERT한다. **순수 함수이므로 같은 입력 → 항상 같은 출력** = 전량 재계산 멱등성(§5.1)의 근거.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
    void 두_번_실행해도_결과가_같다() {  // 전량 재계산 멱등성의 근거 (§5.1)
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
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.analytics.AggregatorTest" --console=plain`
Expected: 컴파일 실패 — `BucketCounts`, `Aggregator` 미존재

- [ ] **Step 3: 최소 구현**

`BucketCounts.java`:
```java
package com.scenelog.analytics;

/** 한 버킷의 유형별 카운트 — segment_stats 한 행에 대응 (기획서 §5.1) */
public record BucketCounts(int laugh, int tension, int touched, int bored) {

    public static final BucketCounts ZERO = new BucketCounts(0, 0, 0, 0);

    public int total() {
        return laugh + tension + touched + bored;
    }

    public BucketCounts plus(com.scenelog.reaction.ReactionType type) {
        return switch (type) {
            case LAUGH   -> new BucketCounts(laugh + 1, tension, touched, bored);
            case TENSION -> new BucketCounts(laugh, tension + 1, touched, bored);
            case TOUCHED -> new BucketCounts(laugh, tension, touched + 1, bored);
            case BORED   -> new BucketCounts(laugh, tension, touched, bored + 1);
        };
    }
}
```

`Aggregator.java`:
```java
package com.scenelog.analytics;

import com.scenelog.reaction.ReactionEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 이벤트 → 버킷별 카운트. 순수 함수라 같은 입력은 항상 같은 출력 —
 * 전량 재계산 + UPSERT 멱등 전략(기획서 §5.1)의 재계산 부분을 담당한다.
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
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.analytics.AggregatorTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/scenelog/analytics/BucketCounts.java src/main/java/com/scenelog/analytics/Aggregator.java src/test/java/com/scenelog/analytics/AggregatorTest.java
git commit -m "feat: 집계기 - 10초 버킷 유형별 카운트, 순수 함수 멱등 (TDD)"
```

---

### Task 5: HighlightDetector — 이동평균 + z-score 피크 검출

**Files:**
- Create: `src/main/java/com/scenelog/analytics/HighlightWindow.java`
- Create: `src/main/java/com/scenelog/analytics/HighlightDetector.java`
- Test: `src/test/java/com/scenelog/analytics/HighlightDetectorTest.java`

**Interfaces:**
- Consumes: `BucketCounts`(Task 4 — 테스트에서 `total()` 사용), `Scenario`/`ReactionSimulator`/`Aggregator`(정확도 테스트에서 전체 파이프라인 연결)
- Produces: `List<HighlightWindow> detect(SortedMap<Integer, Integer> totalsByBucket, int bucketSizeSec)` — 8/3 서비스가 결과를 `highlights` 테이블에 저장(method=`ZSCORE_V1`).

**상수 (기획서 §7 — EDA로 조정하되 시작값은 여기 고정):**
- `MOVING_AVG_WINDOW = 3` (중심 이동평균)
- `Z_THRESHOLD = 2.0`
- `MIN_BUCKETS = 6` (이보다 적으면 통계가 무의미 → 빈 결과)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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

    private SortedMapFixture fixture = new SortedMapFixture();

    /** 편의: int[] → totalsByBucket (10초 간격) */
    static class SortedMapFixture {
        TreeMap<Integer, Integer> of(int... totals) {
            TreeMap<Integer, Integer> m = new TreeMap<>();
            for (int i = 0; i < totals.length; i++) m.put(i * 10, totals[i]);
            return m;
        }
    }

    @Test
    void 평평한_데이터에서는_아무것도_검출하지_않는다() {
        assertThat(detector.detect(fixture.of(5, 5, 5, 5, 5, 5, 5, 5), 10)).isEmpty();
    }

    @Test
    void 명확한_스파이크를_검출한다() {
        // 버킷 5~6(50~70초)에 스파이크
        List<HighlightWindow> result =
                detector.detect(fixture.of(4, 5, 4, 6, 5, 60, 55, 5, 4, 6, 5, 4), 10);
        assertThat(result).hasSize(1);
        HighlightWindow w = result.get(0);
        assertThat(w.startSec()).isLessThanOrEqualTo(50);
        assertThat(w.endSec()).isGreaterThanOrEqualTo(70);
        assertThat(w.score()).isGreaterThan(HighlightDetector.Z_THRESHOLD);
    }

    @Test
    void 버킷이_너무_적으면_빈_결과다() {
        assertThat(detector.detect(fixture.of(1, 50, 1), 10)).isEmpty();
    }

    @Test
    void 시뮬레이터_각본의_정답_피크를_찾아낸다() {  // ★ §5-B-5 — 정확도 측정의 원형
        Scenario inception = new Scenario(1L, 8880, 0.5, List.of(
                new Peak(2400, 2450, ReactionType.TENSION, 15.0),
                new Peak(6100, 6160, ReactionType.TOUCHED, 12.0)
        ));
        List<ReactionEvent> events = new ReactionSimulator().generate(inception, 50, 42L);
        Map<Integer, BucketCounts> buckets = new Aggregator().aggregate(events, 10);
        TreeMap<Integer, Integer> totals = buckets.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().total(), (a, b) -> a, TreeMap::new));

        List<HighlightWindow> detected = detector.detect(totals, 10);

        for (Peak answer : inception.peaks()) {   // 정답 피크마다 ±50초 안에 검출이 있어야 한다
            assertThat(detected).anySatisfy(w -> {
                assertThat(w.startSec()).isBetween(answer.startSec() - 50, answer.startSec() + 50);
            });
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.analytics.HighlightDetectorTest" --console=plain`
Expected: 컴파일 실패 — `HighlightWindow`, `HighlightDetector` 미존재

- [ ] **Step 3: 최소 구현**

`HighlightWindow.java`:
```java
package com.scenelog.analytics;

/** 검출된 하이라이트 구간 — highlights 테이블 한 행에 대응. score는 구간 내 최대 z-score */
public record HighlightWindow(int startSec, int endSec, double score) {}
```

`HighlightDetector.java`:
```java
package com.scenelog.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

/**
 * 버킷 밀도 → 중심 이동평균 → z-score 피크 (기획서 §7, method=ZSCORE_V1).
 * 상수 시작값은 고정하고, EDA(8/6) 결과로 조정 시 근거를 문서화한다.
 */
public class HighlightDetector {

    static final int MOVING_AVG_WINDOW = 3;   // 중심 이동평균 창 (홀수)
    public static final double Z_THRESHOLD = 2.0;
    static final int MIN_BUCKETS = 6;         // 이하면 통계 무의미 → 빈 결과

    public List<HighlightWindow> detect(SortedMap<Integer, Integer> totalsByBucket, int bucketSizeSec) {
        int n = totalsByBucket.size();
        if (n < MIN_BUCKETS) return List.of();

        int[] starts = new int[n];
        double[] values = new double[n];
        int i = 0;
        for (var e : totalsByBucket.entrySet()) {
            starts[i] = e.getKey();
            values[i] = e.getValue();
            i++;
        }

        // 1) 중심 이동평균 (경계는 가능한 범위만 평균)
        double[] smoothed = new double[n];
        int half = MOVING_AVG_WINDOW / 2;
        for (int k = 0; k < n; k++) {
            int from = Math.max(0, k - half), to = Math.min(n - 1, k + half);
            double sum = 0;
            for (int j = from; j <= to; j++) sum += values[j];
            smoothed[k] = sum / (to - from + 1);
        }

        // 2) 평균·표준편차
        double mean = 0;
        for (double v : smoothed) mean += v;
        mean /= n;
        double var = 0;
        for (double v : smoothed) var += (v - mean) * (v - mean);
        double std = Math.sqrt(var / n);
        if (std == 0) return List.of();   // 완전 평탄 — 피크 없음

        // 3) z ≥ 임계값인 연속 버킷을 하나의 구간으로 병합
        List<HighlightWindow> result = new ArrayList<>();
        int windowStart = -1;
        double windowMaxZ = 0;
        for (int k = 0; k < n; k++) {
            double z = (smoothed[k] - mean) / std;
            if (z >= Z_THRESHOLD) {
                if (windowStart < 0) { windowStart = starts[k]; windowMaxZ = z; }
                else windowMaxZ = Math.max(windowMaxZ, z);
            } else if (windowStart >= 0) {
                result.add(new HighlightWindow(windowStart, starts[k - 1] + bucketSizeSec, windowMaxZ));
                windowStart = -1;
            }
        }
        if (windowStart >= 0) {
            result.add(new HighlightWindow(windowStart, starts[n - 1] + bucketSizeSec, windowMaxZ));
        }
        return result;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.scenelog.analytics.HighlightDetectorTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed — **마지막 테스트가 통과하면 "정답 피크 2개 중 2개 검출"이 측정값으로 확보된 것이다 (§11 성과 3)**

- [ ] **Step 5: 전체 테스트 + 커밋**

Run: `.\gradlew.bat test --console=plain`
Expected: `BUILD SUCCESSFUL` — 5개 파일 전체 테스트 통과

```bash
git add src/main/java/com/scenelog/analytics/ src/test/java/com/scenelog/analytics/
git commit -m "feat: 하이라이트 검출기 - 이동평균+z-score, 각본 정답 검증 통과 (TDD)"
```

---

## 이 계획 밖의 일 (혼동 방지)

| 작업 | 언제 | 이 계획과의 관계 |
|---|---|---|
| `TmdbClient` (실제 HTTP 수집) | 8/1 | `TmdbItem`으로 변환해 Validator·Transformer에 공급 |
| Mongo `@Document` 부착, Repository | 8/2 | `ReactionEvent`에 어노테이션 추가 |
| UPSERT (`segment_stats` 저장) | 8/3 | `Aggregator` 결과를 저장 — 멱등성의 DB쪽 절반 |
| `SimulateController` (REST 시연 모드) | 8/2 | `ReactionSimulator.generate()` 호출 후 REST 경로로 전송 |
| Redis 캐시·무효화 | 8/3 | `TimelineService` |
| EXPLAIN 실측 | 8/4 | 시드 모드 100만 행 투입 후 |
