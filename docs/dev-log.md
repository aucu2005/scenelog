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

### day3(반응 수집 + 시뮬레이터)도 당일 진행 (7/31 밤)

- **구현**: ReactionSimulator TDD(4개 green, 계획의 record → Mongo @Id 필요로 클래스화·record식 접근자 유지) → 인덱스 2종을 MongoIndexConfig로 **코드 생성**(자동생성 프로퍼티 불신 — 3호 교훈) → ReactionService 검증 5종 → SimulateService(실제 수집 경로 통과 = 시연이 곧 통합 테스트).
- **실측**: 오디세이(10380초)×20명 → **1883건 적재**, 정답지 TENSION 2802~2852s / TOUCHED 7058~7118s. **재실행 inserted=0, skipped=1883 — 멱등 실증.** 검증 응답: 남의 세션 403 · offset 초과 400 · 배치 501건 400 · 없는 enum 400 · 없는 세션 404.
- **트러블슈팅 4호**: LazyInitializationException — LAZY 연관 + open-in-view=false + @Transactional 부재 조합. readOnly Tx로 해결. **OSIV를 다시 켜지 않은 이유**(커넥션 점유·N+1 은폐)를 기록 — 면접 단골 주제. 상세: `troubleshooting/2026-07-31-lazy-initialization.md`
- **다음(8/1)**: day4(집계 + 하이라이트 검출 + Redis 캐시) — 검출이 answerPeaks를 찾아내면 §11 성과 3번 확보. Redis 프리픽스 검증 잊지 말 것.

## 2026-08-01 (3일차) — day4: 집계 + 검출 + 캐시

- **TDD**: Aggregator·HighlightDetector 8개 green. 발견 1건 — 짧은 시계열(12버킷)은 피크가 σ를 부풀려 자기 z-score를 깎는다(z=1.96<2.0 실측). 테스트 데이터를 현실적 길이(20버킷)로 수정하고 주석에 근거 기록.
- **★ 정답지 채점**: 검출 2800~2860s(정답 2802~2852) · 7050~7120s(정답 7058~7118) — **2/2, 정확도 100%, 버킷 해상도(10s) 내 일치. §11 성과 3번 확보.**
- **멱등**: 집계 2회 실행 → 버킷 820개·하이라이트 2개 동일 (전량 재계산: delete+insert 한 Tx).
- **캐시 실측**: 미스 516ms → 히트 p50 **22.4ms** / p95 39.5ms (~23배). 미스→히트→evict→재생성 사이클 정상.
- **트러블슈팅 5호**: 캐시 저장은 되는데 히트만 500 — record는 final이라 GenericJackson2JsonRedisSerializer(NON_FINAL 정책)가 타입 메타데이터를 안 심는다. 타입 명시 Jackson2JsonRedisSerializer로 교체. **교훈: 캐시는 미스·히트·무효화 세 경로를 다 검증해야 한다.**
- **Redis 프리픽스 숙제 해결**: REDIS_HOST=bogus 부정 테스트 → health 503 DOWN = `spring.data.redis.*` Boot 4 정상 바인딩 (Mongo와 달리 이동 안 함).
- **다음**: day5(대량 시드 + 인덱스 실측 — §11 성과 1번) 또는 EDA 그래프. 여전히 이틀 선행.

### day5(대량 시드 + 인덱스 실측)도 당일 진행 (8/1 오후) — §11 정량 성과 3/3 완성

- **SeedRunner 구현**: TDD 3개 green(각본 baseline 역산·seed- 프리픽스 변환·피크 유지) → `@Profile("seed")` + ApplicationRunner. 콘텐츠 2~51 × 세션 200(PostgreSQL 실제 생성 — §5.4 고아 금지 유지) × 세션당 ~120건, `bulkOps(UNORDERED)` 5천 건 배치. 이벤트 있는 콘텐츠는 통째로 건너뛰어 멱등(세션 증식도 방지).
- **시드 실측**: **1,203,759건 / 129초** (총 1,205,642건, 세션 10,000행). REST 시연 모드로는 수 시간 걸릴 규모.
- **★ 인덱스 실측 (§11 성과 1번)**: explain — COLLSCAN `totalDocsExamined 1,205,642 → nReturned 239`, 7,041ms → IXSCAN **239건만 스캔, 202ms (스캔량 1/5,044, ~35배)**. 집계 API 20회 — p50 6,222→**2,129ms**, p95 9,280→**2,367ms** (~3~4배).
- **정직한 분석**: API가 explain만큼 안 빨라진 이유 — 집계는 대상 콘텐츠 23,325건을 어차피 fetch·계산·저장한다. 인덱스는 "무관한 118만 건 스캔"만 없앤다. 더 줄이려면 증분 집계(README 한계 절). 반대편: segment_stats(1,690행)는 Seq Scan이 0.468ms — **마트는 인덱스가 필요 없을 만큼 작다는 것 자체가 fact/mart 분리의 가치.**
- **계획 조정 2건**: ① "인덱스 드랍 후 시드" 순서는 시드 앱 기동 시 MongoIndexConfig가 인덱스를 되살려 실효 없음 → 시드는 인덱스 있는 채로, 드랍은 서빙 앱 뜬 뒤에. ② 타임라인 API는 Mongo를 안 타므로(PG+Redis) 측정 대상을 집계 API로 교체.
- **장애 주입 (우대 2)**: `docker stop scenelog-mongo` → 드라이버 감지(code 11600) → 30초 serverSelectionTimeout 대기 → `MongoTimeoutException`→500. `docker start` 후 **앱 재시작 없이 자동 재연결.** 교훈: 사용자는 에러보다 30초 무응답을 먼저 겪는다 — 빠른 실패엔 serverSelectionTimeoutMS 조정.
- **트러블슈팅 6호**: `troubleshooting/2026-08-01-mongo-collscan-index.md` (COLLSCAN→IXSCAN 실측 + 왼쪽 접두사 + 장애 주입).
- **테스트**: 34개 전부 green (기존 31 + SeedRunner 3).
- **다음**: day6(EC2 배포 — 자격요건 3) 준비. README에 벌크 적재 트레이드오프 문구 반영은 day7 README 작업에 포함.

### day6(EC2 배포)도 당일 진행 (8/1 저녁) — 자격요건 3 확보, URL 가동

- **★ 배포 완료**: http://13.124.60.61:8080 — health UP·Swagger 200 외부 접속 확인. t3.micro(서울), Terraform 2리소스(보안그룹+EC2), Budget Alert $5 설정.
- **컨테이너화**: Dockerfile 멀티스테이지(JDK 빌드→JRE alpine) + compose **profile 분리**(`up -d`=DB만, `--profile app`=전체 — 로컬 개발 흐름 유지). 로컬에서 4컨테이너 healthy 검증 후 배포.
- **Terraform 판단**: 사용자 요청으로 CLI 대신 IaC — 보안그룹(8080만 공개, 22는 내IP/32, DB포트 미개방) + user_data(swap 2GB·Docker·compose 플러그인·clone 자동화). 인프라가 코드 증거로 남음.
- **OOM 0회**: 계획서가 "거의 확실"이라던 OOM을 4겹 선제 대응(swap 자동화·힙 384m·mem_limit·로컬 사전 검증)으로 회피 — dmesg 빈 출력 + free/docker stats 실측 기록 (7호).
- **겪은 문제 2건**: ① user_data clone이 Dockerfile push보다 먼저 — 빌드 직후 발견, push→pull로 해결. ② EC2 첫 signup 400 — nickname 누락. 로컬은 기존 계정 덕에 안 탔던 경로. "첫 배포는 상태 의존성 테스트다" (7호).
- **시연 데이터**: EC2에서 ETL 재실행(200건 수집→193 적재·**5건 격리** — 로컬 4건과 다름, TMDB 목록이 날마다 달라서) → 시뮬레이션 1,646건 → 집계 698버킷 → **검출 2/2 (EC2에서도 100%)**.
- **README 최소 버전 신규**: URL·데모 계정·숫자 3개·벌크 시드 트레이드오프 문구. day7에 비개발자용 3문단으로 확장.
- **Elastic IP 추가 (사용자 결정)**: 기본 공인 IP는 stop/start 시 반납되는 임대 번호 → 서류에 적을 URL이 흔들림. `aws_eip`를 Terraform에 추가해 **고정 IP 13.124.60.61** 확보 (구 54.180.30.61 폐기). 이제 인스턴스를 껐다 켜도 URL 불변.
- **PS 5.1 인코딩 사고 1건**: IP 치환을 PowerShell `Get-Content`(BOM 없는 UTF-8을 CP949로 오독)로 했다가 한글 문서 3개 파손 → git checkout 복원 후 **ASCII 치환은 sed로**(바이트 안전). 커밋된 파일이라 무손실 복구.
- **배포 학습 가이드 (md+html)**: `docs/배포-구현-가이드.*` — 컨테이너화·IaC·EC2 낱말사전·보안·메모리·검증 절차 (6부작째).
- **다음**: day7 — README 완성·EDA/대시보드(반나절 손절)·ottProject-aws 검토·서류. 인스턴스는 심사 기간 계속 가동.

## 2026-08-02 (4일차) — day7: 포장 (서류 제외 전부)

- **이력서 숫자 3개 문장화 + 팀 프로젝트 협동심 소재**: 저장소 밖 초안 2건 완성 (숫자 문장 4종 + ott 분석 — 회원 도메인 풀스택·main 동기화 20회 실증·자소서 문단 2버전).
- **README 완성**: 비개발자 3문단 + mermaid 아키텍처·ERD + 정량 성과 표(근거 링크) + 기술 판단 4건 + 한계 5건 + 회고 + attribution.
- **시연 GIF**: 배포 서버 Swagger에서 타임라인·하이라이트 실호출 33프레임 녹화 → README 삽입.
- **EDA 그래프 1장**: EC2 실서버 응답으로 반응 밀도 꺾은선 + 검출 구간 음영 — 피크 2개와 검출 영역이 정확히 겹치는 그림. README 최상단 배치.
- **최종 점검 5항목**: URL UP ✓ · .env 이력 없음 ✓ · attribution ✓ · 커밋 연속(7/31~8/2) ✓ · **회사명 2파일 발견 → 조치**: 내부 인수인계 문서(HANDOFF)를 로컬 전용으로 전환(gitignore), 계획 인덱스 표기 중립화 → 재검 0건.
- **남은 것**: 서류(이력서·자기소개서) — 사용자 주도로 진행 예정. 초안 재료는 저장소 밖 2개 파일에 준비됨.
