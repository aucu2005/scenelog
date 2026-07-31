# MongoDB 데이터가 엉뚱한 데이터베이스(test)에 저장된 문제

> 트러블슈팅 로그 3호 · 2026-07-31

## 이슈

ETL 실행 결과 품질 리포트는 "격리 4건"이라고 보고했는데, 확인해 보니
`scenelog` 데이터베이스의 `rejected_records`·`raw_content` 컬렉션이 **비어 있었다.**

- PostgreSQL(contents 195건)은 정상 — 즉 파이프라인 자체는 돌았다
- 에러도 예외도 전혀 없었다 — **조용히 잘못된 곳에 쓰고 있었다**

## 원인 (추적 과정)

1. "쓰긴 썼는데 조회가 안 된다" → **다른 곳에 썼을 가능성**을 의심
2. `db.adminCommand('listDatabases')`로 전체 DB 목록 확인
3. → **`test` 데이터베이스에 `raw_content`·`rejected_records`가 있었다**

`test`는 MongoDB의 **기본 데이터베이스 이름**이다. 즉 우리가 설정한 URI의 DB명(`scenelog`)이
무시되고 기본값으로 붙은 것. 호스트도 기본값(localhost)이라 연결은 멀쩡히 됐다.

**근본 원인**: Spring Boot 4에서 MongoDB 설정 프리픽스가 이동했다.

```yaml
# Boot 3까지 (우리가 쓴 것 — Boot 4에서는 조용히 무시된다)
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/scenelog

# Boot 4
spring:
  mongodb:
    uri: mongodb://localhost:27017/scenelog
```

**인식되지 않는 설정 키는 에러가 아니라 무시**되기 때문에, 잘못을 알려주는 신호가 없었다.

## 왜 위험했나 — 로컬에서는 티가 안 난다

| 환경 | 옛 프리픽스로 두면 |
|---|---|
| 로컬 | 기본 host=localhost가 우연히 맞아서 **연결됨**. DB명만 test로 어긋남 |
| **EC2 (compose)** | `MONGO_HOST=mongo`(서비스명)가 무시되고 localhost로 붙으려다 **기동 실패** |

즉 이 버그는 로컬 개발 내내 숨어 있다가 **배포일(8/5)에 터질 예정**이었다.
"격리 4건이 어디 갔지?"라는 사소한 의문을 그냥 넘기지 않은 덕에 미리 잡았다.

## 대응

1. `application.yml`에서 `spring.data.mongodb.uri` → `spring.mongodb.uri`로 이동
2. `test` DB의 잘못 저장된 컬렉션 삭제 (drop)
3. ETL 재실행으로 **경험적 검증**: `scenelog` DB에 raw_content 199건·rejected_records 4건 정상 적재 확인

부수 효과: 재실행이 곧 **멱등성의 실전 증명**이 됐다 —
같은 200건을 다시 돌리자 `inserted=0, updated=196` (중복 생성 없음, 전부 UPSERT 갱신).

## 재발 방지

- **다른 스토어(Redis)의 프리픽스도 의심 대상** — Boot 4에서 `spring.data.redis.*`가 유효한지
  day4(캐시 연동) 시작 시 같은 방법(실제 쓰고 위치 확인)으로 검증한다
- 메이저 버전 업그레이드 시 설정 키는 "에러가 안 나니 맞겠지"가 통하지 않는다 —
  **실제로 값이 반영됐는지 확인**하는 절차를 둔다 (연결 로그의 host/DB명 확인)
- "카운트가 안 맞는" 종류의 사소한 불일치는 **원인을 확인하기 전까지 넘어가지 않는다**

## 교훈

> **조용한 실패(silent failure)가 시끄러운 실패보다 위험하다.**
> 에러가 나면 그 자리에서 고치지만, 조용히 잘못되면 배포일에 터진다.
> 이번 건은 "품질 리포트의 숫자와 실제 데이터가 일치하는가"를 검증하는 습관이 잡아냈다 —
> 데이터 품질 관리(공고 담당업무 6)가 검증하는 대상에는 **파이프라인 자기 자신도 포함**된다.
