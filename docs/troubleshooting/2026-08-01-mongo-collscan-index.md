# 120만 건에서 집계 API 6초 — COLLSCAN을 잡는 복합 인덱스 실측

> 트러블슈팅 로그 6호 · 2026-08-01 · day5 성능 실측 (§11 정량 성과 3번)

## 이슈

성능 측정을 위해 반응 이벤트를 1,203,759건 벌크 시드(총 1,205,642건)한 뒤
`content_offset_idx`(contentId, offsetSec)를 드랍하고 측정하자:

- 집계 API(`POST /api/admin/contents/2/aggregate`) 20회: **p50 6,222ms / p95 9,280ms**
- 처리 대상은 콘텐츠 2의 이벤트 **23,325건뿐**인데 매번 6초 이상 걸린다

## 원인

`explain("executionStats")`가 원인을 숫자로 보여준다 (contentId=2, offsetSec 2400~2500 범위 조회):

```
stage               : COLLSCAN
totalDocsExamined   : 1,205,642   ← 컬렉션 전체를 읽고
nReturned           : 239         ← 239건만 돌려준다
executionTimeMillis : 7,041
```

인덱스가 없으면 MongoDB는 조건에 맞는 문서를 찾기 위해 **전체 컬렉션을 순서대로 다 읽는다**(COLLSCAN).
필요한 문서가 0.02%여도 읽는 양은 100%다. 데이터가 195편 × 수십 건이던 시절에는 몇 ms라
전혀 보이지 않던 비용이, 120만 건이 되자 6초로 나타났다 — **풀스캔은 데이터 증가에 비례해 조용히 자란다.**

## 대응

(contentId, offsetSec) 복합 인덱스 재생성 — 앱 재시작만으로 복구된다.
`MongoIndexConfig`가 기동 시 인덱스를 코드로 보장하도록 day3에 설계해 뒀기 때문
(프로퍼티 기반 자동 생성은 Boot 4 프리픽스 이동에 당한 전례가 있다 — 3호).

집계 쿼리는 `findByContentId(contentId)`로 offsetSec 조건이 없지만, 복합 인덱스의
**왼쪽 접두사(leftmost prefix)** 규칙 덕에 같은 인덱스를 탄다 — (contentId, offsetSec) 인덱스는
contentId 단독 조회에도 쓰인다. 인덱스 하나가 범위 조회와 전체 조회를 모두 커버한다.

## 결과

| 지표 | 인덱스 없음 | 인덱스 있음 | 개선 |
|---|---|---|---|
| explain stage | COLLSCAN | FETCH ← IXSCAN | — |
| totalDocsExamined | 1,205,642 | **239** | **1/5,044** |
| explain 실행시간 | 7,041ms | **202ms** | ~35배 |
| 집계 API p50 (20회) | 6,222ms | **2,129ms** | ~2.9배 |
| 집계 API p95 (20회) | 9,280ms | **2,367ms** | ~3.9배 |

### 인덱스가 만능이 아니라는 것도 숫자에 있다

explain은 35배인데 API는 왜 3배인가? 집계는 어차피 콘텐츠의 이벤트 23,325건을
**전부 가져와서**(FETCH) 계산하고 PostgreSQL에 저장한다. 인덱스가 없앤 것은
"관계없는 118만 건을 읽는 비용"(~4초)이지, "필요한 2.3만 건을 가져와 처리하는 비용"(~2초)이 아니다.
남은 2초를 더 줄이려면 인덱스가 아니라 **증분 집계**(전량 재계산 포기)가 필요하다 — README 한계 절 기록 대상.

### 반대편 증거 — 마트 테이블은 인덱스가 필요 없었다

같은 날 PostgreSQL 조회 경로도 확인 (`EXPLAIN ANALYZE`):

```
Seq Scan on segment_stats ... rows=870 ... Execution Time: 0.468 ms
```

segment_stats는 1,690행뿐이라 플래너가 인덱스 대신 Seq Scan을 고르고도 0.5ms다.
**원본(120만)은 인덱스가 필수, 집계가 미리 줄여 놓은 마트(1,690)는 스캔이 더 싸다** —
fact/mart 분리 설계가 조회 경로에서 실제로 값을 하는 순간이다.

## 장애 주입 (같은 날, 우대사항 2)

`docker stop scenelog-mongo`로 의도적 장애를 만들고 관찰:

1. 드라이버가 즉시 감지: `MongoNodeIsRecoveringException (code 11600, InterruptedAtShutdown)` → `ConnectException: Connection refused`
2. 30초(serverSelectionTimeout 기본값) 동안 재연결 대기: `Waiting for server to become available... Remaining time: 29996 ms`
3. 타임아웃 후 `MongoTimeoutException` → Spring이 `DataAccessResourceFailureException`으로 번역 → GlobalExceptionHandler가 **500** 반환
4. `docker start scenelog-mongo` 후 **앱 재시작 없이** 다음 요청부터 자동 재연결·정상 응답(2,001ms)

교훈: 장애 시 사용자는 에러가 아니라 **30초 무응답**을 먼저 겪는다. 빠른 실패가 필요하면
`serverSelectionTimeoutMS`를 낮춰야 한다 (기본 30초는 로컬 API 응답으로는 너무 길다).

## 재발 방지

1. **신규 조회 경로를 추가하면 explain부터 확인** — stage가 COLLSCAN이면 인덱스 검토 (PR 체크리스트)
2. 인덱스 존재는 프로퍼티가 아니라 **코드로 보장** (MongoIndexConfig — 드랍 사고가 나도 재시작이 복구)
3. 작은 데이터로는 풀스캔이 안 보인다 — **성능 주장은 실측 규모의 데이터로만** 한다
4. 복합 인덱스는 왼쪽 접두사부터 설계 — 자주 함께 쓰는 (등호, 범위) 순서로
