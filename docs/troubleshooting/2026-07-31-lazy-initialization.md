# LazyInitializationException — 트랜잭션 밖에서 지연 로딩 프록시 접근

> 트러블슈팅 로그 4호 · 2026-07-31 · JPA에서 가장 유명한 예외를 실제로 만난 기록

## 이슈

시뮬레이션 API(`POST /api/admin/simulate`)가 500을 반환.
서버 로그의 마지막 `Caused by`:

```
org.hibernate.LazyInitializationException: Could not initialize proxy [com.scenelog.content.Content#1] - no session
	at com.scenelog.content.Content$HibernateProxy.getDurationSec(Unknown Source)
	at com.scenelog.reaction.ReactionService.registerBatch(ReactionService.java:41)
```

## 원인

세 가지 설정·코드가 조합되어 발생:

1. `WatchSession.content`는 **`@ManyToOne(fetch = LAZY)`** — 세션을 조회할 때 콘텐츠는
   진짜 객체가 아니라 **프록시(대역)**로 온다. 실제 DB 조회는 처음 접근하는 순간 일어난다.
2. `application.yml`에 **`open-in-view: false`** — 요청이 끝날 때까지 DB 세션을 물고 있는
   OSIV를 껐다 (의도적, 올바른 설정).
3. `ReactionService.registerBatch()`에 **`@Transactional`이 없었다** —
   `findById`가 끝나는 순간 DB 세션이 닫히고, 그 뒤 `session.getContent().getDurationSec()`를
   호출하자 프록시가 DB를 읽으려 했지만 세션이 없다 → 예외.

```
[Tx 없음]  findById(sessionId)  ← 여기서 세션 열렸다 바로 닫힘
           ...
           session.getContent().getDurationSec()   ← 프록시가 "이제 DB 읽어야지" → 세션 없음 → 💥
```

## 대응

`registerBatch`에 `@Transactional(readOnly = true)` 부착 — 메서드 전체가 하나의
트랜잭션(=DB 세션 유지) 안에서 실행되어 지연 로딩이 정상 동작한다.

```java
@Transactional(readOnly = true)   // JPA 읽기만 하므로 readOnly. Mongo 쓰기는 JPA Tx와 무관
public ReactionBatchResponse registerBatch(User user, Long sessionId, ReactionBatchRequest request) {
```

**readOnly인 이유**: 이 메서드가 JPA로는 읽기(세션·콘텐츠 조회)만 한다.
MongoDB 쓰기(반응 저장)는 JPA 트랜잭션과 무관하게 각자 커밋된다.

## 왜 OSIV를 다시 켜지 않았나 (면접 포인트)

`open-in-view: true`(기본값)로 돌리면 이 예외는 사라진다. 하지만:

- OSIV는 **HTTP 응답이 끝날 때까지 DB 커넥션을 점유**한다. 외부 API 호출이 낀 요청이면
  그 시간 내내 커넥션을 물고 있어 커넥션 풀 고갈의 원인이 된다.
- 예외가 사라지는 게 아니라 **"어디서 DB를 읽는지 모르게 되는 것"**이다. 뷰 렌더링 중
  무심코 프록시를 건드려 N+1이 발생해도 보이지 않는다.

**LazyInitializationException은 버그가 아니라 경계 알림이다** — "트랜잭션 경계 밖에서
DB 접근이 일어났다"는 신호. 신호를 끄는 것(OSIV on)보다 경계를 바로잡는 것(@Transactional)이 맞다.

## 재발 방지

- **엔티티의 연관을 건드리는 서비스 메서드에는 트랜잭션 경계를 명시**한다 (읽기면 readOnly)
- 이 예외를 만나면: ① 어느 줄에서 났나(스택 첫 우리-코드 줄) ② 그 메서드에 @Transactional이 있나
  ③ LAZY 연관을 Tx 밖으로 반환하고 있지 않나 순서로 본다
- DTO 변환을 트랜잭션 안에서 끝내면 엔티티가 계층 밖으로 새는 것 자체를 막을 수 있다
