# 8/1 — ETL 파이프라인 (수집 → 검증 → 정제 → 적재)

> **이 날이 이 프로젝트에서 가장 중요하다.** 공고 자격요건 5 "데이터 수집·정제·적재 파이프라인 구축"과
> 담당업무 4·6에 정면 대응하는 유일한 날이다. 다른 날을 줄여서라도 이 날은 지킨다.

## 목표

TMDB API에서 영화 데이터를 가져와, **믿을 수 있는 것만 골라** DB에 넣고,
**걸러진 것은 버리지 않고 격리**하며, **그 과정의 숫자를 기록**한다.

## 산출물

- [ ] `POST /api/admin/etl/run` 호출 → 실제 TMDB 데이터가 `contents` 테이블에 적재됨
- [ ] 검증 실패 레코드가 `rejected_records`(MongoDB)에 원본 그대로 격리됨
- [ ] `quality_reports`에 실행 1건당 1행 기록 (수집/적재/누락/중복/정합성 실패 건수)
- [ ] `GET /api/admin/etl/quality-reports`로 조회 가능
- [ ] **§11 정량 성과 2번의 숫자 확보** ← 이게 진짜 목적

## 작업 순서

### 1단계 — 순수 로직부터 (TDD) ★ 여기부터
[core-five-TDD.md](core-five-TDD.md)의 **Task 1(ContentValidator)**, **Task 2(ContentTransformer)**를 그대로 수행한다.

**왜 이것부터인가**: DB도 네트워크도 없이 테스트로 검증되는 부분이다.
뒤의 HTTP·저장 작업이 시간에 쫓겨 못 끝나도, **이 두 개와 테스트가 있으면 "검증·정제 능력"은 증명된다.**

```
.\gradlew.bat test --tests "com.scenelog.etl.*" --console=plain
```

### 2단계 — 저장소 3종

| 파일 | 책임 |
|---|---|
| `etl/RawContent.java` | MongoDB `@Document`. TMDB 응답 원본 JSON 그대로 보관 `{tmdbId, fetchedAt, payload}` |
| `etl/RejectedRecord.java` | MongoDB `@Document`. 검증 탈락분 `{source, tmdbId, payload, rejectReason, rejectedAt, reprocessedAt}` |
| `etl/QualityReport.java` | JPA 엔티티. 실행 1건 = 1행 (아래 컬럼) |
| `etl/RawContentRepository.java` · `RejectedRecordRepository.java` | `MongoRepository` 상속 |
| `etl/QualityReportRepository.java` | `JpaRepository` 상속 |

`QualityReport` 컬럼: `reportId` · `batchRunAt` · `source` · `fetchedCnt` · `insertedCnt` · `updatedCnt` ·
`missingFieldCnt` · `duplicateCnt` · `integrityFailCnt` · `rejectedCnt` · `durationMs` · `details`(JSONB)

> `details`는 `@JdbcTypeCode(SqlTypes.JSON)` + `String` 또는 `Map<String,Object>`로 매핑한다.
> JSONB 사용 자체가 PostgreSQL 활용 어필이다.

### 3단계 — TMDB 수집 (`etl/TmdbClient.java`)

```
GET /discover/movie?page=1..N&language=ko-KR        → 목록 (페이지당 20건)
GET /movie/{id}?append_to_response=credits,keywords&language=ko-KR   → 상세
```

**반드시 지킬 것 3가지:**

| 항목 | 이유 |
|---|---|
| `append_to_response=credits,keywords` **꼭 포함** | 지금은 안 쓰지만 나중에 다시 500번 호출할 수 없다 (기획서 §13-7) |
| 호출 사이 지연 + 429 재시도 | rate limit. 연속 호출하면 차단된다 |
| 받는 즉시 `raw_content`에 저장 | 중간에 끊겨도 이미 받은 건 건너뛰고 재개 가능해진다 |

`RestClient`(Spring 6+) 사용. `application.yml`에 `tmdb.api-key`·`tmdb.base-url`이 이미 있다.

### 4단계 — 오케스트레이션 (`etl/EtlService.java`)

```
for each 수집 항목:
    raw_content 저장
    ContentValidator.validate(item, 기존_tmdbId_집합)
        ├ OK        → ContentTransformer.transform() → contents INSERT
        ├ DUPLICATE → contents UPDATE (UPSERT)
        └ REJECTED  → rejected_records 저장
    카운터 누적
마지막에 quality_reports 1행 저장
```

### 5단계 — 관리자 API (`etl/EtlAdminController.java`)

`POST /api/admin/etl/run` · `GET /api/admin/etl/quality-reports` · `POST /api/admin/etl/reprocess`

**전부 `ROLE_ADMIN` 필요** — SecurityConfig에 이미 설정돼 있다. 관리자 계정이 없으므로
DB에서 직접 승격한다:

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE email = '내이메일';
```

## 검증

```
docker exec scenelog-postgres psql -U scenelog -d scenelog -c "SELECT count(*) FROM contents;"
docker exec scenelog-postgres psql -U scenelog -d scenelog -c "SELECT * FROM quality_reports ORDER BY report_id DESC LIMIT 1;"
docker exec scenelog-mongo mongosh scenelog --quiet --eval "db.rejected_records.countDocuments()"
```

**품질 리포트에 0이 아닌 숫자가 찍히는 것이 이 날의 핵심 성과다.**
`missing_field_cnt`가 0이면 오히려 의심해야 한다 — TMDB의 `runtime`은 비어 있는 경우가 흔하다.

## 🚨 손절 기준

| 상황 | 대응 |
|---|---|
| 수집이 반나절을 넘김 | 페이지 수를 25→10(200건)으로 축소. **검증·격리 구조가 핵심이지 건수가 아니다** |
| MongoDB 연동이 막힘 | `raw_content`·`rejected_records`를 PostgreSQL 테이블로 대체. 폴리글랏 어필은 포기하되 파이프라인은 유지 |
| JSONB 매핑이 막힘 | `details`를 `TEXT`로 저장. JSONB 어필 포기 |
| 관리자 API가 막힘 | `CommandLineRunner`로 앱 시작 시 1회 실행. **데이터가 들어가는 게 우선** |

## 커버하는 공고 항목

- 자격요건 5 — 데이터 수집·정제·적재 파이프라인 구축 ★
- 담당업무 4 — 외부 데이터 수집(API 연동) 및 ETL/배치
- 담당업무 6 — 데이터 품질(정합성·누락·중복) 관리 ★
- 핵심역량 **꼼꼼함** — 실패 데이터를 버리지 않고 격리·재처리하는 설계
