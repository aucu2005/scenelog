# 개발 일지

형식: 날짜별 3줄 — 한 일 / 만난 문제(이슈·원인·대응) / 내일 할 일.
큰 트러블슈팅은 `docs/troubleshooting/` 에 별도 파일로 승격한다.

---

## 2026-07-30 (1일차)

- **한 일**: 저장소 생성(.gitignore 첫 커밋), .env 구성(TMDB 키는 기존 프로젝트에서 이관 — 유효성 실호출로 확인), docker-compose(PostgreSQL 16 + Mongo 7 + Redis 7, t3.micro 대비 메모리 상한 명시), Spring Boot 4.1.0(Java 17) 스캐폴드 + Security 골격.
- **판단 2건**: ① Java 21 계획 → 설치된 17로 확정(Boot 4 최소 기준 충족, 설치 시간 0). ② application.yml을 커밋하기로 변경 — 시크릿을 전부 환경변수 참조로 빼면 yml에 비밀이 없고, clone 즉시 실행 가능한 저장소가 된다. 기존 프로젝트가 yml을 통째로 gitignore한 것은 키 하드코딩이 원인이었으므로 원인 쪽을 제거.
- **내일(7/31)**: JWT 회원가입/로그인 + ROLE 인가 + contents/sessions CRUD + Swagger 확인.
