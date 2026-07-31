# Spring이 `.env`를 읽지 않아 앱이 기동하지 못한 문제

> 트러블슈팅 로그 2호 · 2026-07-31 · 신입 개발자 관점의 상세 해설판
> 같은 내용의 읽기 좋은 버전: [2026-07-31-spring-env-not-loaded.html](2026-07-31-spring-env-not-loaded.html)

---

## 한 줄 요약

**Spring Boot는 `.env` 파일을 자동으로 읽지 않는다.** `.env`는 Docker Compose·Node.js 진영의 관례일 뿐,
Spring 입장에서는 그냥 텍스트 파일이다. IDE에서 실행하면 그 안의 값이 주입되지 않아 설정이 빈 값이 된다.

---

## 1. 이슈 — 증상

IntelliJ에서 앱을 실행하자 기동하지 못하고 수십 줄짜리 스택트레이스와 함께 종료됐다.

```
org.springframework.beans.factory.UnsatisfiedDependencyException:
Error creating bean with name 'authController' ...
```

**바로 전까지 잘 되던 코드였고, 같은 코드를 다른 방식으로 실행했을 때는 정상 기동했다.**
그래서 "코드가 잘못됐나?"를 먼저 의심했지만, 결론적으로 코드는 문제가 없었다.

---

## 2. 스택트레이스 읽는 법 ★ 이 절이 가장 쓸모 있다

에러 메시지가 50줄이 넘어가면 신입은 대개 맨 위만 보고 당황한다. **읽는 순서가 정해져 있다.**

### 규칙: 맨 아래 `Caused by`가 진짜 원인이다

Java의 예외는 **양파처럼 겹쳐서** 전달된다. 위쪽은 "그래서 나도 실패했다"는 **결과**이고,
맨 아래가 **최초로 터진 지점**이다.

이번 경우를 위에서 아래로 풀어보면:

```
authController를 못 만들었다
   └ 왜? authService가 없어서
        └ 왜? jwtProvider가 없어서
             └ 왜? jwtProvider 생성자에서 예외가 났다
                  └ 왜? ★ "jwt.secret이 너무 짧습니다(0바이트)"   ← 진짜 원인
```

Spring은 부품(Bean)을 조립해서 앱을 만든다. **부품 하나가 없으면 그걸 쓰는 부품도 못 만들어지고,
그게 연쇄적으로 위로 전파된다.** 그래서 스택트레이스가 길어진다. 길이에 겁먹을 필요 없다.

### 실전 요령 3가지

| 요령 | 방법 |
|---|---|
| 1. `Caused by`를 전부 찾아 **마지막 것**을 본다 | IDE에서 `Ctrl+F` → `Caused by` |
| 2. 패키지 이름이 **내 코드**인 줄을 찾는다 | `at com.scenelog.auth.JwtProvider.<init>(JwtProvider.java:50)` ← 내 파일 50번째 줄 |
| 3. `at org.springframework...`, `at java.base...` 줄은 **건너뛴다** | 라이브러리 내부라 볼 필요 없다 |

이번 경우 2번 요령으로 `JwtProvider.java:50`을 바로 찾을 수 있었다.

---

## 3. 원인 — 3단계 추적

### 1단계: 생성자가 설정값을 요구했다

```java
// src/main/java/com/scenelog/auth/JwtProvider.java
public JwtProvider(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiry-seconds}") long expirySeconds) {
```

`@Value("${jwt.secret}")` = "설정에서 `jwt.secret`이라는 값을 찾아 여기에 넣어줘"

### 2단계: 설정 파일이 그 값을 환경변수에게 넘겼다

```yaml
# src/main/resources/application.yml
jwt:
  secret: ${JWT_SECRET:}
```

**`${이름:기본값}` 문법**이다. 콜론 뒤에 아무것도 없으므로 **기본값 = 빈 문자열("")**이다.

| 쓰는 법 | 못 찾았을 때 |
|---|---|
| `${JWT_SECRET}` | `Could not resolve placeholder 'JWT_SECRET'` 에러 |
| `${JWT_SECRET:}` | 조용히 `""` 사용 ← 이번 케이스 |
| `${JWT_SECRET:hello}` | 조용히 `"hello"` 사용 |

### 3단계: 빈 문자열이 들어가 검증에 걸렸다

```java
byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);   // "" → 길이 0
if (bytes.length < 32) {
    throw new IllegalStateException(
        "jwt.secret이 너무 짧습니다(%d바이트). 32바이트 이상 필요 — .env의 JWT_SECRET을 확인하세요."
            .formatted(bytes.length));
}
```

에러 메시지의 **"(0바이트)"가 바로 이 빈 문자열**이었다.

---

## 4. 진짜 원인 — Spring은 `.env`를 모른다

프로젝트 폴더에 `.env` 파일이 있고 그 안에 `JWT_SECRET=...`이 분명히 적혀 있었다.
그런데도 못 찾은 이유는, **Spring이 설정값을 찾는 장소 목록에 `.env`가 없기 때문**이다.

```
Spring이 값을 찾는 순서 (위가 더 강함)

  1순위   실행 명령줄 인자      java -jar app.jar --jwt.secret=xxx
  2순위   OS 환경변수          ← JWT_SECRET을 여기서 찾으려 했다
  3순위   application.yml
  4순위   코드에 쓴 기본값

  ✗       .env 파일            ← 목록에 없다!
```

`.env`는 **Docker Compose와 Node.js 진영의 관례**다. Docker Compose는 `.env`를 자동으로 읽지만,
Spring은 그런 약속을 한 적이 없다. 폴더에 있어도 쳐다보지 않는다.

### "왜 어떤 실행 방식은 되고, IDE에서는 안 되나?"

터미널에서 아래 명령을 먼저 실행한 뒤 앱을 띄우면 정상 동작했다:

```powershell
Get-Content .env | Where-Object { $_ -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    [System.Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim())
}
```

이 명령이 하는 일은 **`.env`를 한 줄씩 읽어 OS 환경변수로 수동 등록**하는 것이다.
그러면 위 표의 **2순위**에서 값이 발견된다.

IntelliJ의 ▶ Run 버튼은 이런 과정을 하지 않는다. **같은 코드, 다른 환경.**
개발에서 가장 흔한 함정인 **"제 컴퓨터에서는 되는데요"**의 축소판이다.

> 💡 이런 문제를 만나면 **"코드가 다른가?"보다 "환경이 다른가?"를 먼저 의심**하는 습관이 시간을 아낀다.
> 코드는 git이 똑같음을 보장해 주지만, 환경은 아무도 보장해 주지 않는다.

---

## 5. 대응 — 설정 한 줄 추가

**Java 코드는 한 글자도 바꾸지 않았다.** `application.yml`에 한 줄만 추가했다.

```diff
  spring:
    application:
      name: scenelog

+   config:
+     import: "optional:file:.env[.properties]"
+
    datasource:
      url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/...
```

### 문법 분해

```
"optional:file:.env[.properties]"
 └───┬───┘└─┬─┘└─┬─┘└─────┬────┘
     │      │    │        └─ 이 파일을 .properties 형식으로 해석하라
     │      │    └────────── 읽을 파일 이름
     │      └─────────────── 파일 시스템에서 찾아라 (classpath 아님)
     └────────────────────── 파일이 없어도 에러 내지 말고 그냥 넘어가라
```

| 조각 | 없으면 어떻게 되나 |
|---|---|
| `optional:` | **EC2 서버나 CI에는 `.env`가 없다.** 이게 없으면 그런 환경에서 앱이 아예 안 뜬다 |
| `file:` | `classpath:`가 기본이라 `src/main/resources` 안에서 찾는다. 우리 `.env`는 프로젝트 루트에 있다 |
| `[.properties]` | Spring은 **확장자로 파일 형식을 판단**한다. `.env`는 모르는 확장자라 "properties 형식이야"라고 알려줘야 한다 |

### 왜 하필 `.properties` 형식인가?

두 형식이 사실상 같기 때문이다:

```properties
# .properties 형식              # .env 파일
DB_USER=admin                   JWT_SECRET=abc123xyz
DB_PASS=secret                  TMDB_API_KEY=06a5...
# 주석은 샵으로                  # 주석도 샵
```

**한 줄에 `이름=값`, `#`으로 주석** — 완전히 동일하다. 그래서 그대로 읽힌다.

### 배포 환경과 충돌하지 않는 이유

`spring.config.import`로 읽은 값은 **OS 환경변수보다 우선순위가 낮다.**

```
   강함  ↑   OS 환경변수         ← EC2에서는 여기에 진짜 값 (이게 이긴다)
         │   .env (import)      ← 로컬 개발용
   약함  ↓   application.yml 기본값
```

즉 **로컬에서는 `.env`가, 서버에서는 진짜 환경변수가** 쓰인다.
한 줄로 두 환경을 모두 만족시킨 셈이다.

---

## 6. 다른 방법은 왜 안 골랐나

| 방법 | 문제점 |
|---|---|
| `application.yml`에 값 직접 쓰기 | **public 저장소라 시크릿이 그대로 유출된다.** 절대 안 됨 |
| IntelliJ EnvFile 플러그인 | 그 사람 IDE에서만 동작. 저장소에 남지 않아 다른 컴퓨터·CI에서 또 막힌다 |
| OS 환경변수로 영구 등록 | 동작은 하지만 **눈에 보이지 않는 설정**이라, 새 컴퓨터에서 원인 모를 실패를 반복한다 |
| 실행할 때마다 셸에서 주입 | 매번 해야 하고, **IDE의 ▶ Run 버튼으로는 안 된다** |
| ✅ `spring.config.import` | 설정이 저장소에 남고, Run 버튼으로 그냥 되고, 서버에서도 안전 |

**선택 기준**: "이 설정이 **저장소 안에 남아서**, **누가 어디서 clone 해도** 동작하는가?"

---

## 7. 여기서 배운 것 2가지

### ① fail-fast — 일찍, 크게, 명확하게 실패시켜라

생성자에 넣어둔 이 검사가 이번 문제를 **기동 시점에** 잡아냈다:

```java
if (bytes.length < 32) {
    throw new IllegalStateException(
        "jwt.secret이 너무 짧습니다(0바이트). 32바이트 이상 필요 — .env의 JWT_SECRET을 확인하세요.");
}
```

**이 검사가 없었다면?** 앱은 멀쩡히 떴다가, 나중에 **누군가 로그인을 시도하는 순간**
`WeakKeyException` 같은 알 수 없는 500 에러가 났을 것이다.
그때는 "로그인이 왜 안 되지?"부터 시작해 훨씬 오래 헤맸을 것이다.

| | 기동 시점에 실패 | 사용 시점에 실패 |
|---|---|---|
| 발견 시기 | 즉시 | 한참 뒤, 어쩌면 운영 중 |
| 에러 메시지 | 원인과 해결법 명시 | 라이브러리 내부 예외 |
| 영향 | 나만 | 사용자 |

**필수 설정값은 생성자에서 검증하라.** 그리고 **에러 메시지에 해결 방법을 함께 적어라.**
(`".env의 JWT_SECRET을 확인하세요"` 한마디가 디버깅 시간을 크게 줄인다.)

### ② `${VAR:}` 의 빈 기본값은 함정이 될 수 있다

```yaml
jwt.secret: ${JWT_SECRET:}    # 못 찾아도 조용히 빈 문자열 → 나중에 이상한 데서 터짐
jwt.secret: ${JWT_SECRET}     # 못 찾으면 즉시 "Could not resolve placeholder" → 원인 명확
```

**기본값을 줄 때는 "이 값이 없어도 앱이 정상인가?"를 먼저 물어야 한다.**

| 설정 | 기본값을 줘도 되나 |
|---|---|
| `server.port` | ✅ 없으면 8080 쓰면 됨 |
| `POSTGRES_HOST` | ✅ 로컬 개발은 localhost |
| **`JWT_SECRET`** | ❌ **없으면 앱이 존재할 이유가 없다** |

시크릿·API 키처럼 **없으면 안 되는 값은 기본값을 주지 않는 게 더 안전하다.**

---

## 8. 다음에 비슷한 일이 생기면 — 체크리스트

### 앱이 기동하지 않을 때

- [ ] 스택트레이스 **맨 아래 `Caused by`**를 먼저 읽었는가
- [ ] `at com.내패키지...`로 시작하는 줄을 찾아 **내 파일 몇 번째 줄**인지 확인했는가
- [ ] 에러 메시지를 **그대로 읽었는가** (이번엔 메시지에 답이 있었다)

### "다른 데선 되는데 여기선 안 될 때"

- [ ] 코드가 아니라 **환경 차이**를 먼저 의심했는가
- [ ] 환경변수가 실제로 들어갔는지 확인했는가
- [ ] 실행 방법이 다른가 (IDE / gradlew / java -jar / docker)

### 설정값이 비어 있을 때 확인 순서

1. `application.yml`에 그 키가 있는가
2. `${환경변수이름}`이 맞는 이름인가 (오타·대소문자)
3. 그 환경변수가 **실제로 존재**하는가
   - PowerShell: `$env:JWT_SECRET`
   - Linux/Mac: `echo $JWT_SECRET`
4. `.env`에만 있고 환경변수로는 없는 상태 아닌가 ← **이번 케이스**

### 값이 잘 주입됐는지 빠르게 확인하는 법

```
# application.yml에 임시 추가 (확인 후 반드시 제거)
logging:
  level:
    org.springframework.boot.context.config: DEBUG
```

Spring이 **어떤 설정 파일들을 어떤 순서로 읽었는지** 로그로 보여준다.

> ⚠️ 시크릿 값 자체를 로그로 찍지 말 것. 로그 파일에 남아 유출 경로가 된다.

---

## 9. 재발 방지

1. **필수 설정값은 생성자에서 fail-fast 검증** + 에러 메시지에 해결법 명시
2. **시크릿에는 기본값(`:`)을 주지 않는다**
3. `.env` 같은 비표준 설정 파일은 **`spring.config.import`로 명시적으로 편입**한다
4. `.env.example`을 저장소에 두어 **어떤 키가 필요한지** 알 수 있게 한다 (값은 비운 채로)

---

## 10. 면접에서 이 경험을 말한다면

> "로컬에서 앱이 기동하지 않는 문제가 있었습니다.
> 스택트레이스의 마지막 `Caused by`를 보니 JWT 시크릿이 0바이트라는 메시지가 있었고,
> 원인은 **Spring이 `.env` 파일을 자동으로 읽지 않는다**는 것이었습니다.
> `.env`는 Docker Compose 쪽 관례이고 Spring의 설정 소스 목록에 없어서,
> IDE로 실행할 때는 값이 주입되지 않았습니다.
>
> `spring.config.import`로 `.env`를 설정 소스에 편입해서 해결했는데,
> `optional:`을 붙여 파일이 없는 서버 환경에서도 기동되게 했고,
> OS 환경변수가 우선순위가 더 높다는 점을 이용해 **로컬은 `.env`, 서버는 실제 환경변수**를
> 쓰도록 한 줄로 정리했습니다.
>
> 이 문제를 빨리 찾을 수 있었던 건 생성자에 시크릿 길이 검증을 넣어둬서
> **기동 시점에 명확한 메시지로 실패**했기 때문입니다. 그 검사가 없었다면
> 로그인 시점에 원인 불명의 500이 났을 겁니다."

**면접관이 이어서 물을 만한 것**

- "왜 `optional:`을 붙였나요?" → 서버·CI에는 `.env`가 없으므로. 없어도 기동돼야 한다
- "서버에서는 시크릿을 어떻게 넣나요?" → OS 환경변수. import보다 우선순위가 높아 자동으로 덮어쓴다
- "시크릿을 코드에 넣으면 안 되는 이유는?" → 저장소 이력에 영구히 남는다. 파일을 지워도 과거 커밋에 남아 있다
