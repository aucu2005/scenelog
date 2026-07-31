# ETL 구현 가이드 (신입용)

> day2에서 만든 것을 처음부터 다시 이해하는 문서.
> **모든 코드는 실제 우리 저장소의 코드다** — `src/main/java/com/scenelog/etl/` 에 있는 그대로.
> 읽는 목표: 각 코드 조각에 대해 **"왜 이렇게 했는가"를 남에게 설명할 수 있게 되는 것.**

---

## 1. ETL이 뭔가 — 식당 주방 비유

ETL = **E**xtract(추출) · **T**ransform(변환) · **L**oad(적재). 외부의 데이터를 가져와 내 시스템에 넣는 과정 전부다.

식당 주방으로 바꿔 말하면:

| 단계 | 주방에서는 | 우리 코드에서는 |
|---|---|---|
| **E** 추출 | 시장에서 식자재 사오기 | `TmdbClient` — TMDB API에서 영화 200건 받아오기 |
| (검수) | 상한 것 골라내기 | `ContentValidator` — 누락·중복·정합성 검사 |
| **T** 변환 | 손질하기 (껍질 벗기고 자르기) | `ContentTransformer` — 분→초, 빈 문자열→null |
| **L** 적재 | 냉장고에 정리해 넣기 | `EtlService` — PostgreSQL `contents`에 저장 |

**검수가 핵심이다.** 공고가 요구하는 "데이터 품질(정합성·누락·중복) 관리"가 바로 이 단계이고,
day2 작업의 절반이 여기에 들어갔다. 상한 식자재를 그냥 냉장고에 넣으면 나중에 음식이 다 망가진다 —
잘못된 데이터를 그냥 적재하면 집계·분석이 다 틀어지는 것과 같다.

---

## 2. 전체 지도 — 영화 1건이 지나가는 길

```
TMDB API
   │  ① TmdbClient.fetchMovieDetail(id)      ← E: 원본 JSON 받기
   ▼
raw_content 저장 (MongoDB)                    ← 원본은 손대기 전에 무조건 보관
   │
   │  ② ContentValidator.validate(...)       ← 검수: 3갈래로 나뉜다
   ▼
 ┌─────────────┬──────────────┬──────────────┐
 │ OK          │ DUPLICATE    │ REJECTED     │
 │ (정상)      │ (이미 있음)   │ (문제 있음)   │
 ▼             ▼              ▼
 ③ Transformer  ③ Transformer   rejected_records
 ▼             ▼              (MongoDB 격리)
 contents      contents        │
 INSERT        UPDATE          └→ 나중에 /reprocess로 재도전
   │
   ▼
④ quality_reports에 실행 결과 1행            ← 몇 건 받아서, 몇 건 넣고, 몇 건 걸렀나
```

오늘 실제로 이 길을 통과한 결과:

```
수집 200건 → 적재 195건 · 격리 4건 · (같은 배치 내 중복 1건)
재실행     → inserted=0, updated=196        ← 멱등성 증명
```

---

## 3. 실제 영화 3편의 여정 — 이걸 이해하면 전부 이해한 것

### 여정 A: "인셉션" — OK 경로

**① TMDB가 준 원본 (실제 형태):**
```json
{
  "id": 27205,
  "title": "인셉션",
  "original_title": "Inception",
  "runtime": 148,                // ← 분 단위!
  "release_date": "2010-07-15"
}
```

**② 검증** — `ContentValidator.validate()`:
- id 있음 ✓ · 제목 있음 ✓ · runtime 148분(0~600 사이) ✓ · 개봉일이 과거 ✓ · 처음 보는 id ✓
- → **`Status.OK`**

**③ 정제** — `ContentTransformer.transform()`:
```
runtime: 148 (분)      →  durationSec: 8880 (초)     ← × 60
"2010-07-15" (문자열)  →  LocalDate(2010, 7, 15)     ← 타입 변환
"movie"               →  ContentType.MOVIE           ← enum 매핑
```

**④ 적재** — `contents` 테이블에 INSERT. 끝.

### 여정 B: "어벤져스: 둠스데이" — REJECTED 경로 (오늘 실제로 격리된 영화)

**① 원본:**
```json
{ "id": ..., "title": "어벤져스: 둠스데이", "runtime": 165, "release_date": "2026-12-16" }
```

**② 검증**: 개봉일이 **2026-12-16 = 미래**다. 아직 개봉 안 한 영화.
- → **`Status.REJECTED`**, 사유 `INTEGRITY_FAIL:release_date`

**③ 격리** — 버리지 않는다:
```
rejected_records 컬렉션에 저장:
{ source: "TMDB", tmdbId: ..., payload: {원본 전체}, rejectReason: "INTEGRITY_FAIL:release_date",
  rejectedAt: ..., reprocessedAt: null }
```

> **왜 미개봉작을 거르나?** 일반 영화 DB라면 미개봉작도 정상 데이터다. 하지만 SceneLog는
> **시청 반응 분석 서비스**다 — 아직 볼 수 없는 영화에는 시청 세션이 존재할 수 없다.
> 같은 데이터라도 **서비스의 목적에 따라 유효성의 기준이 달라진다.** 면접에서 이 질문이 나오면
> 이렇게 답하면 된다. (그리고 12월이 지나 개봉하면? → `/reprocess`가 재검증해서 통과시킨다!)

### 여정 C: 재실행된 "인셉션" — DUPLICATE 경로

2차 실행에서 인셉션이 또 왔다. 이미 `contents`에 있다.

**② 검증**: 기존 tmdb_id 집합에 27205가 있음 → **`Status.DUPLICATE`**

**③ 갱신**: INSERT가 아니라 **UPDATE** (제목이나 상영시간이 바뀌었을 수 있으니 덮어쓴다):
```java
case DUPLICATE -> {
    c.duplicate++;
    TmdbNormalized n = transformer.transform(item);
    contentRepository.findByTmdbId(n.tmdbId())
            .ifPresent(existing -> {
                existing.updateFrom(n.title(), n.contentType(), n.durationSec(), n.releaseDate());
                c.updated++;
            });
}
```

이래서 2차 실행 결과가 `inserted=0, updated=196`이었다. **같은 걸 몇 번 돌려도 데이터가 불어나지 않는다 = 멱등(idempotent).**

> **멱등이 왜 중요한가?** 배치는 실패하고 재실행되는 게 일상이다. 재실행할 때마다 데이터가
> 2배, 3배로 불어나면 재실행을 못 한다. "몇 번을 돌려도 결과가 같다"는 성질이 있어야
> 마음 놓고 재실행 버튼을 누를 수 있다.

---

## 4. 코드 비교로 배우기 — 나쁜 예 vs 우리 코드

### 비교 1: 실패 데이터를 어떻게 다루나 ★ 가장 중요

**😰 흔한 코드 (학습용 프로젝트 대부분):**
```java
for (Integer id : ids) {
    try {
        save(fetch(id));
    } catch (Exception e) {
        // 뭔가 잘못됨. 일단 넘어가자
    }
}
```
실패한 데이터가 **흔적도 없이 증발한다.** 몇 건이 왜 실패했는지 아무도 모른다.
"200건 중 196건만 들어갔네? 뭐지?" — 영원히 알 수 없다.

**✅ 우리 코드 (`EtlService.processOne`):**
```java
case REJECTED -> {
    c.rejected++;
    c.addReason(result.rejectReason());               // ① 사유별 카운트
    rejectedRecordRepository.save(                     // ② 원본째 격리
            new RejectedRecord(SOURCE, item.id(), payload, result.rejectReason()));
}
```
실패는 ① **세어지고** ② **원본과 사유가 함께 보관**된다. 그래서:
- 품질 리포트에 "정합성 위반 4건, 사유: release_date 2건·runtime 2건"이 찍히고
- 격리된 실물을 열어 "아 어벤져스 둠스데이가 미개봉이라 걸렸구나"를 확인할 수 있고
- 규칙을 고치거나 시간이 지나면 `/reprocess`로 되살릴 수 있다

**이 차이가 "데이터 품질 관리 경험 있음"과 없음의 차이다.**

### 비교 2: 한 건의 실패가 배치 전체를 죽이는가

**😰 나쁜 코드:**
```java
public void run() {
    for (Integer id : ids) {
        process(id);        // 137번째에서 예외 → 나머지 63건은 시도조차 못 함
    }
}
```

**✅ 우리 코드 (`EtlService.run`):**
```java
for (Integer tmdbId : ids) {
    try {
        processOne(tmdbId, existingTmdbIds, c);
    } catch (Exception e) {
        log.warn("tmdbId={} 처리 실패: {}", tmdbId, e.getMessage());
        c.rejected++;
        c.addReason("FETCH_ERROR");
        rejectedRecordRepository.save(...);            // 격리하고
    }                                                  // 계속 간다
}
```
try-catch가 **루프 바깥이 아니라 안에** 있다. 한 건이 죽어도 격리하고 다음 건으로 간다.
200건짜리 배치에서 137번째의 네트워크 오류가 전체를 무효로 만들면 안 되기 때문이다.

> 비교 1과 헷갈리기 쉬운데, 차이는 **catch 안에서 뭘 하느냐**다.
> 나쁜 코드는 잡고 **버린다**. 우리는 잡고 **기록·격리한 뒤** 간다.

### 비교 3: 원본을 언제 저장하나

**😰 나쁜 순서:** 검증·변환을 다 통과한 다음 원본 저장
→ 변환에서 예외가 나면 원본도 없다. 뭘 받았었는지 재현 불가.

**✅ 우리 코드 (`EtlService.processOne`) — 받자마자 저장:**
```java
Map<String, Object> payload = tmdbClient.fetchMovieDetail(tmdbId);
c.fetched++;

// 원본을 먼저 보관한다 — 정제가 실패해도 원본은 남아야 재처리가 가능하다
rawContentRepository.findByTmdbId(tmdbId)
        .ifPresentOrElse(existing -> { /* 이미 있으면 다시 저장하지 않는다 */ },
                () -> rawContentRepository.save(new RawContent(tmdbId, payload)));

TmdbItem item = toItem(payload);          // 여기서부터 뭐가 실패해도
ValidationResult result = validator...    // 원본은 이미 안전하다
```

이유는 **비용의 비대칭**이다:
- 수집(API 호출) = 비싸다. rate limit 있고, 느리고, 상대 서버 사정에 달림
- 정제(내 코드 실행) = 싸다. 언제든 다시 돌릴 수 있음

→ 비싼 것(원본)은 확보 즉시 보관하고, 싼 것(정제)은 몇 번이고 다시 한다.
나중에 "장르도 저장할걸"이 되면 API 500번을 다시 부르는 게 아니라 `raw_content`에서 다시 뽑는다.

### 비교 4: 외부 API를 예의 있게 부르기

**😰 나쁜 코드:**
```java
for (int id : ids) {
    results.add(restClient.get()...);    // 200연발 → 429 Too Many Requests → 전멸
}
```

**✅ 우리 코드 (`TmdbClient`):**
```java
private static final long CALL_INTERVAL_MS = 60;     // ① 호출 사이 간격
private static final int MAX_RETRY = 3;

private <T> T withRetry(Supplier<T> call) {          // ② 실패 시 재시도
    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            long backoff = 500L * (1L << (attempt - 1));   // ③ 500ms → 1s → 2s
            sleep(backoff);
        }
    }
    throw last;
}
```
③이 **지수 백오프(exponential backoff)**다. 실패할 때마다 대기를 2배로 늘린다.
서버가 "지금 바빠"(429)라고 하는데 같은 간격으로 재시도하면 부하를 더 얹는 셈이다.
점점 길게 물러나면서 서버가 회복할 시간을 준다 — 외부 API 연동의 표준 예절.

### 비교 5: 로직 클래스에 왜 @Component가 없나

**😰 습관적 코드:**
```java
@Component
public class ContentValidator {
    public ValidationResult validate(...) {
        if (date.isAfter(LocalDate.now())) ...    // 진짜 오늘 날짜 — 테스트가 날짜에 따라 흔들린다
    }
}
```

**✅ 우리 코드:**
```java
public class ContentValidator {                    // 어노테이션 없음 — 순수 Java
    private final Clock clock;
    public ContentValidator(Clock clock) { this.clock = clock; }

    ... date.isAfter(LocalDate.now(clock)) ...     // 시계를 주입받는다
}
```
테스트에서는 시계를 **고정**해서 만든다:
```java
// ContentValidatorTest — 2026-07-30에 고정. 1년 뒤에 돌려도 같은 결과
private final Clock fixed = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);
private final ContentValidator validator = new ContentValidator(fixed);
```
Spring 없이 `new`로 바로 만들 수 있으니 테스트가 **0.1초**에 돈다.
빈 등록은 `EtlConfig`가 따로 한다:
```java
@Configuration
public class EtlConfig {
    @Bean public Clock clock() { return Clock.systemDefaultZone(); }
    @Bean public ContentValidator contentValidator(Clock clock) { return new ContentValidator(clock); }
}
```
**로직과 프레임워크를 분리하면, 로직은 어디서든(테스트·다른 프레임워크·main 메서드) 재사용된다.**

### 비교 6: 검증과 정제를 왜 다른 클래스로 나눴나

한 클래스에 다 넣을 수도 있다. 나눈 이유는 **대답하는 질문이 다르기 때문**이다:

| | ContentValidator | ContentTransformer |
|---|---|---|
| 질문 | "받아들일 것인가?" | "어떤 모양으로 바꿀 것인가?" |
| 출력 | OK / DUPLICATE / REJECTED | TmdbNormalized (변환된 데이터) |
| 실패하면 | 격리 (rejected_records) | — (검증 통과분만 오므로 실패 없음) |

Transformer 주석에 적어둔 전제가 중요하다:
```java
 * 전제: ContentValidator를 통과한 item만 받는다 (id·displayTitle 존재 보장).
```
검증이 앞에서 다 걸러주니 Transformer는 null 체크 없이 단순해진다.
**각자 하나의 책임만 맡으면 각자 단순해진다** — 단일 책임 원칙의 실물이다.

---

## 5. 품질 리포트 — 오늘의 최종 산출물

실행이 끝나면 `quality_reports`에 1행이 남는다. 오늘 실제 데이터:

```json
{
  "fetchedCnt": 200,        // 받아온 건수
  "insertedCnt": 195,       // 신규 적재
  "updatedCnt": 1,          // 중복 → 갱신
  "duplicateCnt": 1,
  "integrityFailCnt": 4,    // 정합성 위반
  "rejectedCnt": 4,         // 격리 총계
  "durationMs": 67758,
  "details": {
    "reasonBreakdown": { "INTEGRITY_FAIL:release_date": 2, "INTEGRITY_FAIL:runtime": 2 }
  }
}
```

`details`는 PostgreSQL **JSONB** 컬럼이다:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> details;
```
**왜 JSONB인가?** 실패 사유의 종류는 앞으로 계속 늘어난다. 사유마다 컬럼을 파면
스키마 변경이 끝없이 필요하다. "구조가 유동적인 부가 정보"는 JSONB에 담는 게 PostgreSQL식 해법이다.
(핵심 지표들은 반대로 고정 컬럼이다 — 집계·비교가 필요하니까. **둘의 구분이 설계다.**)

### 이 숫자가 왜 중요한가

이력서의 이 문장이 오늘 만들어졌다:

> "TMDB 200건 수집 중 정합성 위반 4건을 검증 단계에서 검출·격리하고 재처리 경로를 구현"

**"열심히 했습니다"가 아니라 숫자다.** 그리고 시연 가능하다 — Swagger에서
`GET /api/admin/etl/quality-reports`를 부르면 지금도 이 숫자가 나온다.

---

## 6. 오늘 예상이 틀렸던 것 — 이것도 학습이다

기획서는 "TMDB의 runtime은 null이 흔하다"고 예상했다. **실측: 인기 상위 200건 중 누락 0건.**

이유를 추론하면: 인기 영화일수록 커뮤니티가 메타데이터를 잘 관리한다. 누락은 아마
인기 순위 밖 롱테일에서 나올 것이다.

**예상이 틀렸다는 걸 숨기지 않고 기록하는 것** — 이게 데이터를 다루는 사람의 태도다.
"결측치가 많을 줄 알았는데 상위권 데이터는 깨끗했다. 표본을 어디서 뽑느냐가 품질 분포를 바꾼다"는
발견은 EDA(공고 담당업무 5)의 실제 사례가 된다.

---

## 7. 면접 예상 질문과 답

**Q1. "ETL 파이프라인을 만들어 보셨다고요? 뭘 신경 쓰셨나요?"**
> 세 가지입니다. ① 실패 데이터를 버리지 않고 원본과 사유를 함께 격리해서 재처리할 수 있게 했고,
> ② 한 건의 실패가 배치 전체를 죽이지 않도록 건별로 격리 후 계속 진행하게 했고,
> ③ 몇 번을 재실행해도 데이터가 불어나지 않게(멱등) UPSERT로 적재했습니다.
> 실제로 같은 200건을 두 번 돌려 inserted=0을 확인했습니다.

**Q2. "미개봉 영화를 왜 걸러내나요? 정상 데이터 아닌가요?"**
> 일반 영화 DB라면 정상입니다. 하지만 저희 서비스는 시청 반응을 분석하므로,
> 시청이 불가능한 콘텐츠에는 세션이 존재할 수 없습니다. 유효성의 기준은 서비스 목적에 따라
> 달라진다고 판단했고, 개봉 후에는 재처리 API로 되살릴 수 있게 했습니다.

**Q3. "원본을 왜 따로 보관하나요? 저장 공간 낭비 아닌가요?"**
> 수집은 비싸고(rate limit·네트워크) 정제는 쌉니다. 정제 규칙이 바뀔 때 원본이 있으면
> API를 다시 부르지 않고 재처리만 하면 됩니다. 저장 비용보다 재수집 비용이 훨씬 크다고 판단했습니다.

**Q4. "품질 지표는 어떻게 관리하나요?"**
> 실행마다 수집·적재·누락·중복·정합성 위반 건수를 한 행으로 남기고,
> 사유별 분포는 JSONB로 저장합니다. 지표와 실제 데이터가 일치하는지도 확인하는데,
> 실제로 이 대조 덕분에 데이터가 엉뚱한 DB로 들어가던 설정 버그를 배포 전에 잡았습니다.

---

## 8. 스스로 확인하기 — 이 질문에 답할 수 있으면 오늘 학습 완료

1. REJECTED와 DUPLICATE의 처리가 어떻게 다른가? 왜 DUPLICATE는 격리하지 않나?
2. try-catch가 루프 안에 있는 것과 밖에 있는 것의 차이는?
3. 원본(raw_content)을 검증 **전에** 저장하는 이유는?
4. `Clock`을 주입받는 이유는? `LocalDate.now()`를 직접 쓰면 무슨 문제가 생기나?
5. 2차 실행에서 inserted=0이 나온 게 왜 "성공"인가?
6. details 컬럼만 JSONB이고 나머지 카운트는 고정 컬럼인 이유는?

> 답이 막히면 해당 절을 다시 읽고, 그래도 막히면 실제 코드
> (`EtlService.java`, `ContentValidator.java`)를 열어 주석과 함께 따라가 볼 것.

---

## 관련 문서

- [트러블슈팅 3호 — Mongo가 엉뚱한 DB에 저장](troubleshooting/2026-07-31-mongo-wrong-database.md) (오늘의 조용한 실패)
- [day2 계획서](plans/day2-0801-ETL.md) (이 구현의 설계도)
- [core-five TDD 계획](plans/core-five-TDD.md) (검증기·정제기의 테스트 우선 개발 기록)
- [JWT 구현 가이드](JWT-구현-가이드.md) (day1 학습 자료)
