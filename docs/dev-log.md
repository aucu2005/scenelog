# 개발 일지

형식: 날짜별 3줄 — 한 일 / 만난 문제(이슈·원인·대응) / 내일 할 일.
큰 트러블슈팅은 `docs/troubleshooting/` 에 별도 파일로 승격한다.

---

## 2026-07-30 (1일차)

- **한 일**: 저장소 생성(.gitignore 첫 커밋), .env 구성(TMDB 키는 기존 프로젝트에서 이관 — 유효성 실호출로 확인), docker-compose(PostgreSQL 16 + Mongo 7 + Redis 7, t3.micro 대비 메모리 상한 명시), Spring Boot 4.1.0(Java 17) 스캐폴드 + Security 골격.
- **판단 2건**: ① Java 21 계획 → 설치된 17로 확정(Boot 4 최소 기준 충족, 설치 시간 0). ② application.yml을 커밋하기로 변경 — 시크릿을 전부 환경변수 참조로 빼면 yml에 비밀이 없고, clone 즉시 실행 가능한 저장소가 된다. 기존 프로젝트가 yml을 통째로 gitignore한 것은 키 하드코딩이 원인이었으므로 원인 쪽을 제거.
- **내일(7/31)**: JWT 회원가입/로그인 + ROLE 인가 + contents/sessions CRUD + Swagger 확인.

## 2026-07-31 (2일차)

- **한 일**: 1일차 완료 기준 최종 달성 — compose 3컨테이너(PG·Mongo·Redis) healthy, `/actuator/health` 200 `{"status":"UP"}`, Swagger UI 200. 스캐폴딩·핵심 5파일 TDD 실행 계획 커밋(총 4커밋).
- **트러블슈팅**: Docker Desktop 4.49.0이 시작 직후 종료 — 손상된 유닉스 소켓 파일(`dockerInference`)을 삭제하지 못해 Inference manager 초기화 실패. 로그(`com.docker.backend.exe.log`)로 원인 특정 → 삭제 불가 파일은 부모 디렉토리 rename으로 우회 + 최신 버전 업데이트로 해소. 상세: `troubleshooting/2026-07-31-docker-desktop-crash.md` (로그 1호).
- **오늘 할 일**: GitHub 원격 연결 + push → 이후 핵심 5파일 TDD(계획서 Task 1부터) 또는 JWT 인증 — 일정표 기준 판단.
- **추가**: GitHub 원격 연결 완료(github.com/aucu2005/scenelog, public). 인증·도메인 뼈대 구축 — 엔티티 3종(User·Content·WatchSession), Repository, DTO, 전역 예외 처리, Swagger JWT 설정, 콘텐츠/세션 CRUD. JWT 핵심 3개(JwtProvider·AuthService·JwtAuthenticationFilter)는 TODO 골격 + 실패 테스트 6개로 남김 → `docs/TODO-today.md` 참고.
- **검증**: 컴파일 성공, 앱 기동 `/actuator/health` 200, Swagger 200, `/api/contents` 200, signup 501(미구현 명시), 잘못된 입력 400(검증 동작), 인증 필요 경로 403. JPA가 테이블 3개 생성 확인 — `users.password_hash`가 의도대로 `VARCHAR(100)`.
- **판단 1건**: `JwtAuthenticationFilter`에 `@Component`를 붙이지 않고 `SecurityConfig`에서 직접 생성. 붙이면 Spring Boot가 서블릿 필터로도 자동 등록해 필터가 두 번 실행된다.
- **JWT 3종 직접 구현 완료** (JwtProvider·AuthService·JwtAuthenticationFilter) — JwtProviderTest 6개 green.
- **이슈/원인/대응**: 앱 기동 실패 `jwt.secret이 너무 짧습니다(0바이트)` / 원인: Spring은 `.env`를 자동으로 읽지 않는다 — IDE 실행 시 환경변수 미주입, `${JWT_SECRET:}`가 빈 기본값으로 해석됨. 생성자의 fail-fast 검사가 기동 시점에 잡아줌(없었으면 로그인 순간 원인불명 500) / 대응: `spring.config.import: optional:file:.env[.properties]` 한 줄로 Spring이 .env를 직접 읽게 함. OS 환경변수가 있으면 그쪽이 우선하므로 EC2 배포와도 충돌 없음.
- **최종 검증 (day1 체크리스트)**: signup 201 · 중복 409 · login 200+토큰 · 틀린비번 401 · 토큰으로 me/history 200 · 일반유저 admin 403 · 변조토큰 403. **자격요건 6(인증/인가) 달성.**

### day2(ETL)를 하루 앞당겨 진행 (7/31 저녁)

- **구현**: 검증기·정제기 TDD(테스트 13개 green) → 저장소 3종(raw_content·rejected_records·quality_reports/JSONB) → TmdbClient(페이지네이션·60ms 간격·429 지수백오프) → EtlService(개별 실패 격리, 배치 지속) → 관리자 API 3종.
- **실전 수집 결과 (§11 성과 2번의 숫자)**: TMDB **200건 수집 → 195건 적재, 4건 격리**(미개봉작 release_date 2건 · runtime=0 2건), 68초. 격리 실물: "어벤져스: 둠스데이"(2026-12 개봉예정) 등 — **실제 더러운 데이터를 잡았다.**
- **멱등성 실전 증명**: 같은 200건 재실행 → `inserted=0, updated=196`. 중복 생성 없음.
- **예상과 다른 실측 1건**: 기획서는 "runtime null이 흔하다"고 예상했으나 인기 상위 200건은 **누락 0건** — 인기작은 메타데이터 관리가 잘 되어 있다. 누락은 롱테일에서 나올 것. 정직하게 기록.
- **설계 판단 1건**: 미개봉작(미래 개봉일)을 격리하는 게 맞는가? 일반 영화 DB라면 정상 데이터지만, SceneLog는 **시청 반응 분석 서비스라 시청 불가능한 콘텐츠는 세션이 존재할 수 없다** → 격리가 방어 가능한 판단. 면접 소재.
- **트러블슈팅 3호**: 품질 리포트는 격리 4건인데 컬렉션이 비어 있음 → listDatabases로 추적 → **Boot 4에서 `spring.data.mongodb.*` → `spring.mongodb.*` 이동**, 옛 키는 조용히 무시되어 기본 DB(test)로 적재됨. 로컬에선 티가 안 나고 **EC2 배포일에 터질 버그**를 미리 잡음. 상세: `troubleshooting/2026-07-31-mongo-wrong-database.md`
- **다음(8/1)**: day3(반응 수집 + 시뮬레이터)로 — 일정 하루 여유 확보. Redis 프리픽스도 day4에 같은 방식으로 검증할 것.
