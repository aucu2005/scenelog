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
