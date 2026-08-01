# SceneLog — 콘텐츠 반응 분석 백엔드

영화·드라마를 보는 시청자들이 "몇 초 지점에서 어떤 반응을 보였는가"를 수집하고,
통계로 **하이라이트 구간을 자동 검출**해 보여주는 백엔드 서비스입니다.

> ⚠️ 이 README는 최소 버전입니다 — 상세 소개·아키텍처 문서는 작성 중입니다.

## 🌐 배포 주소 (EC2, 서울 리전)

| | URL |
|---|---|
| **Swagger (API 문서·실행)** | http://54.180.30.61:8080/swagger-ui/index.html |
| 헬스체크 | http://54.180.30.61:8080/actuator/health |
| 타임라인 예시 (공개 API) | http://54.180.30.61:8080/api/contents/1/timeline |
| 하이라이트 예시 (공개 API) | http://54.180.30.61:8080/api/contents/1/highlights |

**데모 계정** (Swagger 우상단 Authorize에 로그인 응답의 accessToken 입력):

```
email:    tester@scenelog.dev
password: password123!   (ROLE_ADMIN — ETL·시뮬레이터·집계 실행 가능)
```

## 정량 결과 (전부 실측)

| 항목 | 숫자 |
|---|---|
| ETL 데이터 정합성 | TMDB 200건 수집 → 위반 검출·격리 (미개봉작·runtime=0), 재실행 멱등 |
| 하이라이트 검출 정확도 | 심은 정답 피크 **2/2 = 100%** (버킷 해상도 10s 내 일치) |
| 인덱스 효과 (120만 건) | 스캔 1,205,642건·7,041ms → **239건·202ms (1/5,044)** · API p95 9,280→2,367ms |
| 캐시 효과 | 미스 516ms → 히트 p50 **22.4ms** (~23배) |

> **정직한 기록**: 성능 측정용 100만 건은 별도 프로파일의 벌크 적재기로 넣었습니다 (API 검증 5종 우회).
> API 검증은 시연 모드와 단위 테스트 34개로 별도 검증했습니다.
> 상세 과정: [트러블슈팅 로그 7건](docs/troubleshooting/) · [개발 일지](docs/dev-log.md)

## 기술 스택

Spring Boot 4.1.0 (Java 17) · PostgreSQL 16 (사용자·콘텐츠·집계 마트) · MongoDB 7 (반응 이벤트 원본) ·
Redis 7 (타임라인 캐시) · Docker Compose · Terraform (EC2 인프라) — 전 구성 [docker-compose.yml](docker-compose.yml) · [infra/main.tf](infra/main.tf)

## 로컬 실행

```bash
# 1. DB만 (앱은 IDE/gradlew) — 로컬 개발
docker compose up -d && ./gradlew bootRun

# 2. 전체 (배포와 동일 구성)
docker compose --profile app up -d --build
```

`.env` 파일 필요: `TMDB_API_KEY`, `JWT_SECRET` (+ `POSTGRES_*` 선택). 커밋되지 않습니다.

## 문서

- [개발 일지](docs/dev-log.md) — 일자별 결과·판단
- [트러블슈팅 로그](docs/troubleshooting/) — 이슈·원인·대응 7건
- [학습 가이드 5부작](docs/) — JWT · ETL · 반응수집 · 집계검출캐시 · 시드인덱스 (각 md+html)
- [단계별 계획](docs/plans/README.md)
