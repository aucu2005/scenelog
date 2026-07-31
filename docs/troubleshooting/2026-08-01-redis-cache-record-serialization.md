# Redis 캐시가 저장은 되는데 읽기(히트)만 500 — record 직렬화 함정

> 트러블슈팅 로그 5호 · 2026-08-01 · "쓰기는 되는데 읽기만 실패"의 대표 사례

## 이슈

타임라인 캐시 검증 중 이상한 패턴 발견:

| 동작 | 결과 |
|---|---|
| 첫 조회 (캐시 미스 → Redis 저장) | ✅ 200, `scenelog:timeline::1` 키 생성됨 |
| **두 번째 조회 (캐시 히트 → Redis 읽기)** | ❌ **500** |
| 집계 후 무효화(evict) | ✅ 키 삭제됨 |

저장·무효화는 되고 **읽기만 죽는다.** 캐시가 없으면 정상, 있으면 500 — 캐시를 켠 것이 오히려 장애가 되는 상황.

## 원인

로그의 원인 줄:

```
SerializationException: Could not read JSON: Unexpected token (START_OBJECT),
expected VALUE_STRING: need String, Number or Boolean value that contains type id
(for subtype of java.lang.Object)
```

`GenericJackson2JsonRedisSerializer`의 동작 원리와 record의 성질이 충돌했다:

1. 이 직렬화기는 "무엇이든" 저장·복원하기 위해 JSON에 **`@class` 타입 메타데이터**를 심는다.
2. 그런데 타입 메타데이터를 심는 기본 정책이 **NON_FINAL** — final이 아닌 클래스에만 심는다.
3. **record는 항상 final 클래스다** → 우리 `TimelineBucketResponse`(record)는 타입 정보 없이 저장됐다.
4. 읽을 때 역직렬화기는 "타입 id가 있겠지" 하고 열었다가 일반 객체(START_OBJECT)를 만나 실패.

**저장은 아무 검증 없이 성공하고, 읽기에서만 터진다** — 그래서 캐시 미스 경로(저장)만 테스트하면
절대 발견되지 않는다. 캐시 히트 경로를 반드시 별도로 검증해야 하는 이유.

## 대응

캐시 값의 타입을 우리가 이미 알고 있으므로(`List<TimelineBucketResponse>`),
타입을 명시한 `Jackson2JsonRedisSerializer`로 교체 — 메타데이터가 아예 필요 없어진다:

```java
ObjectMapper mapper = new ObjectMapper();
var timelineType = mapper.getTypeFactory()
        .constructCollectionType(List.class, TimelineBucketResponse.class);

... .serializeValuesWith(SerializationPair.fromSerializer(
        new Jackson2JsonRedisSerializer<>(mapper, timelineType)));
```

부수 효과: `@class` 메타데이터가 사라져 저장되는 JSON이 더 작고 깨끗하다.

**검증 (수정 후):**
- 캐시 히트 20회 반복: **p50 22.4ms / p95 39.5ms** (미스 516ms 대비 약 23배)
- 미스 → 히트 → 집계 evict → 재생성 전체 사이클 정상

## 함께 한 검증 — Redis 프로퍼티 프리픽스 부정 테스트

3호(Mongo 프리픽스 이동) 때문에 Redis도 의심스러웠다. **엉터리 호스트를 주입하는 부정 테스트**로 확인:

```
REDIS_HOST=bogus-redis-host 로 기동 → /actuator/health → 503 DOWN
```

값이 무시됐다면 localhost로 붙어 UP이 나왔을 것이다. DOWN = **`spring.data.redis.*`가
Boot 4에서 정상 바인딩된다**는 실증. (Mongo는 이동했지만 Redis는 아니다 — 프레임워크의
변경은 모듈별로 다르므로 하나가 이동했다고 전부 이동했다고 가정해도, 안 했다고 가정해도 안 된다.
**의심되면 부정 테스트가 가장 싸고 확실하다.**)

## 재발 방지

1. **캐시는 미스·히트·무효화 세 경로를 모두 검증한다** — 저장 성공은 읽기 성공을 보장하지 않는다
2. 캐시 값 타입이 고정이면 **타입 명시 직렬화기**를 기본으로 (Generic류는 타입이 진짜 동적일 때만)
3. record·final 클래스 + `GenericJackson2JsonRedisSerializer` 조합은 이 함정이 있음을 기억
4. 설정 프로퍼티가 실제로 읽히는지 의심되면 **부정 테스트** (틀린 값 → 실패해야 정상)
