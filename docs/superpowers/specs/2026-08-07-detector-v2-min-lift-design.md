# 검출기 v2 — 절대 하한(MIN_LIFT) 결합으로 정밀도 개선 설계

> 작성: 2026-08-09 · 상태: 승인됨 · 작업 브랜치: `feature/detector-v2-min-lift` (main 무접촉)

## 1. 배경

골든셋 다양화 실측(2026-08-07, 스펙 `2026-08-07-golden-set-precision-design.md`)이 검출기의
구조적 약점을 규명했다: **정밀도 31.6% (FP 398건)**, 원인은 z-score의 상대성 — 절대 높이
하한이 없어 조용한(무피크) 콘텐츠에서 순수 노이즈가 z≥2.0을 넘는다. FP 분포가 이를 증명한다:
무피크 314 · 약한 피크 63 · 완만한 피크 21, 강한 피크 구조는 전부 0.

처방: 판정 조건을 상대(z) AND 절대(평균 대비 배수 하한)의 2단으로 확장한다.
`Highlight.method` 컬럼의 버저닝 설계("검출 방식을 바꿔도 과거 결과를 지우지 않고 비교")가
처음부터 예정한 확장 경로다 — `ZSCORE_V1` → `ZSCORE_V2`.

## 2. 범위

**포함**
- `HighlightDetector`에 minLift 도입 (생성자 주입, 기본 생성자 = DEFAULT_MIN_LIFT)
- `METHOD_ZSCORE_V2` 추가, 집계 쓰기·삭제를 V2로, 조회에 method 필터 추가
- MIN_LIFT 스윕 선정(튜닝 시드 1~10) + 검증 시드(11~20) 최종 실측
- 평가 테스트 양버전(v1·v2) 고정, dev-log 기록

**제외**
- 최소 지속 시간 조건·median/MAD — 후속 (한 번에 변수 하나)
- `Z_THRESHOLD=2.0` 변경 — 이번 변수는 minLift 하나다
- EC2 접근 — 서버 동결 유지
- 서류 초안(`../이력서-숫자문장-초안.md`) 갱신 — main·서류 정합성 유지, **병합 시점에** 갱신
- application.yml 설정화 — YAGNI (C안 기각 사유)

## 3. 운영 코드 변경 (4파일)

### 3-1. HighlightDetector

```java
public static final double DEFAULT_MIN_LIFT = /* 스윕 선정값 — §4 프로토콜로 결정 후 기입 */;

private final double minLift;

public HighlightDetector() { this(DEFAULT_MIN_LIFT); }
public HighlightDetector(double minLift) { this.minLift = minLift; }

// 판정 (기존 z 조건에 절대 하한 AND 결합)
double z = (smoothed[k] - mean) / std;
if (z >= Z_THRESHOLD && smoothed[k] >= mean * minLift) { ... }
```

- `minLift = 0`이면 절대 하한이 비활성 (`smoothed >= 0`은 항상 참) → **v1과 완전 동일 동작**.
  이것이 하위호환의 수학적 보장이자 평가에서 v1 기준선을 재현하는 장치다.
- `Z_THRESHOLD`·`MOVING_AVG_WINDOW`·`MIN_BUCKETS`는 무변경.
- javadoc에 도입 근거(골든셋 실측 FP 분포)와 minLift=0의 의미를 기록한다.

### 3-2. Highlight — 버전 상수 추가

```java
public static final String METHOD_ZSCORE_V1 = "ZSCORE_V1";   // 보존
public static final String METHOD_ZSCORE_V2 = "ZSCORE_V2";   // z + 절대 하한
```

### 3-3. AggregationService — 쓰기 경로 V2 전환

`deleteAllByContentIdAndMethod(contentId, METHOD_ZSCORE_V2)` + `new Highlight(..., METHOD_ZSCORE_V2)`.
V1 행은 DB에 보존된다 (버저닝 의도). 검출기 인스턴스는 기존처럼 기본 생성자 — DEFAULT_MIN_LIFT 적용.

### 3-4. HighlightRepository + TimelineService — 조회 method 필터

현재 `findByContentIdOrderByStartSec`은 method 무필터라 V1·V2가 공존하면 API가 중복 반환한다.
`findByContentIdAndMethodOrderByStartSec(contentId, METHOD_ZSCORE_V2)`로 교체 —
"버저닝은 쓰기만이 아니라 읽기도 현행 버전을 지정해야 완성된다"를 코드로 기록.

## 4. MIN_LIFT 선정·검증 프로토콜

자기 채점 방지를 위해 튜닝과 검증 데이터를 분리한다 (train/test 분리와 같은 원리).

1. **스윕 (튜닝 시드 1~10)**: k ∈ {0, 1.25, 1.5, 2.0, 2.5, 3.0} × 골든셋 구조 12종.
   k=0 행은 v1 기준선 — **정밀도 31.6%·재현율 98.2%가 재현되지 않으면 스윕 자체가 잘못된 것**
   (중단·원인 규명). 결과표는 `build/reports/min-lift-sweep.md`.
2. **선정 기준 (기계적 적용)**: 튜닝 시드에서 **재현율 ≥ 97%(발견 165/170 이상)를 유지하는
   k 중 정밀도 최대**. 동률이면 작은 k (보수적 — 하한은 낮을수록 안전).
3. **최종 검증 (검증 시드 11~20)**: 선정된 k로 정밀도·재현율 실측 — **이것이 공식 숫자**.
   튜닝 시드의 숫자는 선정 근거로만 쓴다.
4. **실패 시나리오**: 어떤 k도 기준을 못 채우면 개선을 채택하지 않고 스윕 표 자체를
   실측 기록으로 dev-log에 남긴다 (측정 우선 원칙 — 나쁜 결과도 결과다).

## 5. 테스트 전략

- **TDD (검출기 변경)**: 실패 테스트 먼저 —
  ① z는 넘지만 절대량이 `mean × minLift` 미달인 피크는 검출되지 않는다
  ② `minLift = 0`이면 v1과 동일한 결과를 낸다 (기존 스파이크 케이스 재사용 대조)
- **기존 테스트 하위호환**: `HighlightDetectorTest` 4개는 무수정 green이어야 한다 — 기존
  케이스의 스파이크(값 60 vs 기준선 ~5)는 후보 k 최대치(3.0)보다도 높아 하한에 걸리지 않는
  것이 정상. 깨지면 설계 재검토 신호로 취급하고 중단·보고한다.
- **평가 양버전 고정**: `GoldenSetEvalTest`를 실행 헬퍼로 파라미터화해 두 실행을 모두 고정 —
  - v1 고정: `new HighlightDetector(0)` × 시드 1~10 → 기존 **tp=184, fp=398, found=167 그대로 유지**
  - v2 고정: 기본 생성자 × 검증 시드 11~20 → 최종 실측값으로 고정 (170 불변식 동일 적용)
  - 개선 전후가 테스트 코드에 영구 문서화된다.
- 스윕 러너는 테스트 소스에 두되 고정 assert 없이 표 산출만 담당 (선정 도구지 회귀 게이트가 아님).

## 6. 성공 기준·기록

- 검증 시드에서 v2 정밀도가 v1(31.6%) 대비 유의미하게 상승하고 재현율 손실이 §4-2 기준 이내.
- `gradlew test` 전체 green (기존 + 신규. contextLoads는 Docker 가동 시).
- dev-log에 기록: 스윕 표, 선정 근거(기준 적용 과정), 검증 시드 최종 전후 비교
  (v1 31.6%/98.2% → v2 X%/Y%), 남은 한계(구조 8 가림 등 minLift가 못 고치는 것).

## 7. 브랜치·병합 원칙

- 전 작업은 `feature/detector-v2-min-lift`에서. **main·EC2·서류는 무접촉.**
- push·병합·서류 갱신은 작업 완료 후 사용자 결정. 병합 시점에 서류 초안 §5에
  "개선 후속" 문장을 추가하는 것을 권장 (발견→개선 완결 서사).

## 8. 후속 (범위 외, 기록만)

- 최소 지속 시간(2버킷↑) 조건 — 남는 FP가 짧은 노이즈 창이면 다음 변수로.
- median/MAD 교체 — 구조 8(강한 피크의 σ 부풀림이 약한 피크를 가림) 재현율 처방.
- Z_THRESHOLD 스윕 — minLift 확정 후 2차원 지도.
