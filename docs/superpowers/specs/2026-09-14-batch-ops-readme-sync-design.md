# 배치 운영 보강 + README 동기화 설계 (수정계획서 §5 실행)

> 작성: 2026-09-14 · 상태: 승인됨(수정계획서 v1.1 §5-1~§5-5 기준) · 작업 브랜치: `feature/batch-ops-readme-0914`

## 1. 배경

서류(8/19 수정본) → README → 코드 세 층이 서로 다른 숫자를 말한다(수정계획서 §1-3).
README는 테스트 34개·트러블슈팅 7건·가이드 6부작·검출 2/2·quality_reports=MongoDB·
"Spring Scheduling으로 충분"이라 적혀 있고, 코드 실측은 테스트 45개(2026-09-14 `./gradlew test`
전부 통과)·트러블슈팅 8건·가이드 7편·검출기 v2 98.5%/97.6%·quality_reports=PostgreSQL(JSONB)·
`@Scheduled` 0건이다. 면접관은 서류 → README → 코드 순으로 내려가므로 README 갱신이 문장 수정보다 먼저다.

코드 보강 3건은 포트폴리오에 이미 있는 문장("완료 순간 @CacheEvict", "@Scheduled 한 줄로 배치화
가능", "운영 전환 시")을 코드 수준으로 올리는 일이다. 각각 공고 담당업무 7(캐시)·4(배치 파이프라인
운영)·8(모니터링/로깅)에 대응한다.

## 2. 범위와 판단 (§5-5 기준 적용)

| 구분 | 항목 | 결정 | 근거 |
|---|---|---|---|
| 최소 | README 9항목(§5-1) | **한다** | 안 하면 서류 신뢰가 깎인다 |
| 권장 | ② 캐시 evict-커밋 순서 + 테스트 | **한다** | 1~2시간, 면접 질문 하나가 "코드로 답하는 질문"이 된다 |
| 여유 | ① `@Scheduled` batch 프로파일 | **한다** | 오늘 9/14, 제출 목표 9/22 — §7 일정(9/15~17 선택 ①③) 안 |
| 시간이 남으면 | ③ 집계 실행 이력(batch_runs) + 대시보드 + 장애 주입 | **한다** | 모니터링/로깅 갭이 가장 크다. ①과 같은 세션에서 하면 스케줄러가 처음부터 실행 이력을 남긴다 |
| 제외 | ShedLock(분산 락) | **안 한다** | 단일 인스턴스 전제. 의존성·테이블 추가 대비 이득 작음 — README 한계에 "미적용, 필요 시점" 기록 |
| 제외 | Airflow·Kafka | 안 한다 | 새 도구 도입은 §6 보류 항목 |

안 한 것은 한 것처럼 쓰지 않는다. ShedLock 미적용, 캐시 경쟁 창 잔존, 데모 서버 중지는 README에 그대로 적는다.

## 3. 설계

### 3-1. ② 캐시 무효화를 커밋 이후로 (RedisConfig)

**문제.** `AggregationService.aggregate()`에 `@Transactional`과 `@CacheEvict`가 함께 있다.
evict 시점은 두 AOP 프록시의 감싸는 순서로 정해지며, 문서화된 보장이 아니다. evict가 커밋보다
먼저면 그 사이 조회가 커밋 안 된 옛 집계를 읽어 캐시에 다시 넣고 TTL 10분 동안 옛 값이 보인다.

**처방.** `RedisCacheManager.builder(...).transactionAware()` 한 줄. Spring Data Redis가 각
캐시를 `TransactionAwareCacheDecorator`로 감싸 **트랜잭션 동기화가 활성이면 put/evict/clear를
afterCommit으로 미룬다**(트랜잭션이 없으면 즉시 실행, 롤백이면 실행하지 않음). 계획서 방법 A와
동일 효과이며 빈 타입(`RedisCacheManager`)을 유지한다.

**증명(테스트 3건, 실제 Redis·PostgreSQL 사용 — contextLoads와 같은 전제).**
1. 트랜잭션 안에서 evict → 커밋 전에는 키가 남아 있고, 커밋 직후 사라진다.
2. 롤백되면 evict가 실행되지 않는다(DB가 안 바뀌었으니 캐시도 유효).
3. 실제 `aggregate()`를 바깥 트랜잭션에 참여시켜 호출 → 메서드가 끝나도 커밋 전엔 캐시가 남고, 커밋 뒤 지워진다.

**한계(그대로 적는다).** 커밋 직전에 시작한 조회가 삭제 이후에 옛 값을 캐시에 쓰는 경쟁은 남는다.
TTL 10분 안전망으로 완화, 필요하면 지연 이중 삭제.

### 3-2. ① batch 프로파일 정기 집계

- `com.scenelog.analytics.batch.BatchSchedulingConfig` — `@Configuration @EnableScheduling @Profile("batch")`.
- `AggregationScheduler` — `@Component @Profile("batch")`. `@Scheduled(fixedDelayString =
  "${scenelog.batch.aggregate.fixed-delay:PT10M}", initialDelayString =
  "${scenelog.batch.aggregate.initial-delay:PT30S}")`.
- 대상 = `reaction_events`의 distinct `contentId`(복합 인덱스 접두 컬럼이라 인덱스만 읽는다).
  콘텐츠 하나의 실패가 나머지를 막지 않도록 건별 try/catch, 실행 요약 로그 1줄.
- 기본 프로파일(데모·RAM 1GB 서버)에는 이 빈이 아예 없다 — 수동 트리거만 남는다.
- fixedDelay(이전 실행 **종료** 기준)를 쓰는 이유: 120만 건 재집계가 주기보다 오래 걸려도 겹쳐 돌지 않는다.
- 검증: 단위 테스트 2(전체 집계·건별 격리) + 프로파일 배선 테스트 2(batch에 있고 기본에 없다) +
  로컬 batch 프로파일 실행에서 2회 이상 도는 로그 캡처(dev-log).

### 3-3. ③ 집계 실행 이력 batch_runs

- PostgreSQL 테이블 `batch_runs`(엔티티 `BatchRun`): run_id, job_name, content_id, triggered_by
  (MANUAL/SCHEDULED), status(RUNNING/SUCCESS/FAILED), started_at, finished_at, duration_ms,
  event_count, bucket_count, highlight_count, error_type, error_message(500자).
- `BatchRunRecorder` — start/succeed/fail 각각 `REQUIRES_NEW`. **실패 기록이 집계 트랜잭션과
  운명을 같이하면 실패했을 때 기록도 사라진다.** 별도 트랜잭션이라 집계가 롤백돼도 FAILED 행이 남는다.
- `AggregationJobRunner.run(contentId, trigger)` — 기록 → 집계 → 성공/실패 기록 → 예외는 그대로
  전파. 관리자 API(MANUAL)와 스케줄러(SCHEDULED)가 이 하나를 통과한다.
- `AggregationService.aggregate()`는 `Map`이 아니라 `AggregationResult` 레코드를 반환하도록
  바꾼다(카운트를 타입으로 읽기 위해). 응답 JSON 모양은 `toResponse()`로 동일 유지.
- 조회 API: `GET /api/batch-runs?limit=` **공개**(대시보드용 — 오류는 예외 타입까지만),
  `GET /api/admin/batch-runs?limit=` 관리자(오류 메시지 포함). 공개 응답에 메시지를 빼는 이유:
  Mongo 호스트명 같은 내부 정보가 메시지에 섞일 수 있다.
- 대시보드에 "최근 배치 실행" 카드(시작·대상·트리거·상태·이벤트→버킷→하이라이트·소요·오류).
- 검증: 단위 테스트 2(성공/실패 기록·재전파) + 통합 테스트 2(바깥 트랜잭션 롤백에도 FAILED 행 잔존,
  성공 행의 카운트·소요) + **장애 주입**: MongoDB 컨테이너 중지 → 집계 → FAILED 행 → 재기동 →
  재실행 SUCCESS 행. 결과 JSON과 대시보드 화면을 dev-log·docs/images에 남긴다.

### 3-4. README 9항목 (§5-1) — 반영 방식

| # | 항목 | 반영 |
|---|---|---|
| 1 | 테스트 34개 | 45개 → 이번 세션에서 추가되는 테스트까지 포함한 **최종 `./gradlew test` 결과 값**으로 (마지막에 기록) |
| 2 | 트러블슈팅 7건 | 8건 |
| 3 | 가이드 6부작 | 6부작 + 프로젝트종합 가이드 (7편) |
| 4 | 검출 2/2 | 골든셋 120회(12구조×10시드, 검증 시드 11~20) v1 정밀도 31.6%/재현율 98.2% → v2 98.5%/97.6% + dev-log 8/9 링크. 대시보드 타일도 같은 숫자로 |
| 5 | mermaid MongoDB quality_reports | quality_reports를 PostgreSQL 노드로(`@Entity`+JSONB 확인), MongoDB는 rejected_records(`@Document` 확인)만. batch_runs 추가 |
| 6 | "Spring Scheduling으로 충분" | 현재 상태: 기본 프로파일 수동 트리거, batch 프로파일 `@Scheduled` 정기 집계(로컬 검증 로그), 실행 이력 batch_runs. 분산 락 미적용 명시 |
| 7 | 서류 기준 커밋 | 상단 한 줄. 커밋 해시는 README 커밋 자체가 바꾸므로 **태그 `submission-2026-09`**로 고정(최종 커밋에 태그) |
| 8 | 데모 URL | EC2 중지(9/11 결정) — "가동 중" 삭제, 배포·운영은 과거형(2026-08-01~09, Terraform·OOM 0회 근거 유지), **로컬 10분 재현 절차** 절 신설(관리자 승격 psql 포함), 스크린샷 캡션을 캡처 시점 명시로 |
| 9 | 어디다있소 README | `C:\Users\aucu2\Project\daiso`(dev) README에 "최종 MVP 구조(2026-02-25)" 절 추가 — 구조 사실은 코드 재확인 후 작성, 재측정 표는 gold.json 검수(동국님) 전이므로 대괄호 항목은 채우지 않고 남긴다 |

## 4. 제외·후속

- ShedLock — 필요 시점(인스턴스 2대 이상)만 기록.
- 캐시 지연 이중 삭제 — 경쟁 창이 실제 문제로 관측되면.
- median/MAD 검출기 — 구조 8 약한 피크 4개 미검출(기존 후속 과제).
- 어디다있소 코드 수정 2건(`retrieval.py`, `index_to_external.py`) 커밋 — 스냅샷 폴더에만 있음, 동국님 검수 후.
