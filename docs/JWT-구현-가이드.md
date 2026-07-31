# JWT 구현 가이드 (신입용)

> 읽기 좋은 버전: [JWT-구현-가이드.html](JWT-구현-가이드.html) (목차·색상 박스·다크모드)
>
> 코드를 그대로 옮겨 적어도 된다. **중요한 건 타이핑이 아니라 "왜 이렇게 하는가"를 설명할 수 있게 되는 것**이다.
> 면접에서 나오는 질문은 "코드 보여주세요"가 아니라 "왜 이렇게 하셨어요?"다.

---

## 1. JWT가 뭔가 — 놀이공원 팔찌 비유

로그인한 사용자를 서버가 어떻게 기억할까?

**옛날 방식(세션)**: 서버가 "3번 손님은 홍길동" 이라고 **명단을 들고 있는다**.
→ 손님이 많아지면 명단이 무거워지고, 서버를 여러 대로 늘리면 명단을 공유해야 한다.

**JWT 방식**: 서버가 **위조 불가능한 팔찌**를 채워 보낸다. 팔찌에 "홍길동, 42번, 오후 3시까지 유효"가 적혀 있다.
→ 서버는 아무것도 기억하지 않는다. 팔찌만 보면 되니까. 이게 **무상태(stateless)**다.

팔찌를 위조하지 못하는 이유는 **서명(signature)** 때문이다.
서버만 아는 비밀키로 도장을 찍어두면, 내용을 한 글자라도 바꾸는 순간 도장이 안 맞는다.

### 토큰 생김새
```
eyJhbGciOiJIUzI1NiJ9  .  eyJzdWIiOiI0MiIsImV4cCI6MTc...  .  4f2Xb9...
└─── 헤더 ───┘            └────── 내용(payload) ──────┘      └ 서명 ┘
   무슨 알고리즘?              누구인지 · 언제까지               위조 방지 도장
```

⚠️ **중요**: 가운데 "내용" 부분은 **암호화가 아니라 인코딩**이다. 누구나 열어볼 수 있다.
→ 그래서 **비밀번호 같은 걸 토큰에 넣으면 안 된다.** 우리는 userId·email·role만 넣는다.

---

## 2. 우리가 만들 3개가 각각 하는 일

| 메서드 | 팔찌 비유 | 언제 호출되나 |
|---|---|---|
| `createToken` | 팔찌를 **만들어 준다** | 로그인 성공 시 (`AuthService.login`) |
| `isValid` | 팔찌가 **진짜인지 확인** | 모든 요청마다 (`JwtAuthenticationFilter`) |
| `getUserId` | 팔찌에서 **누구인지 읽는다** | 진짜로 확인된 후 |

---

## 3. `createToken` 작성하기

파일: `src/main/java/com/scenelog/auth/JwtProvider.java`

```java
public String createToken(Long userId, String email, Role role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirySeconds * 1000);

    return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("email", email)
            .claim("role", role.name())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact();
}
```

### 한 줄씩 설명

| 줄 | 뜻 |
|---|---|
| `new Date()` | 지금 시각 |
| `now.getTime() + expirySeconds * 1000` | `getTime()`은 **밀리초**를 주는데 우리 설정(`expiry-seconds`)은 **초** 단위라 1000을 곱해 단위를 맞춘다. 3600초 = 1시간 뒤 |
| `.subject(...)` | 토큰의 "주인공". 표준 필드라 이름이 정해져 있고, 문자열만 받으므로 `String.valueOf`로 변환 |
| `.claim("email", ...)` | 표준에 없는 값은 `claim`으로 자유롭게 넣는다 |
| `.claim("role", role.name())` | `role.name()`은 enum을 문자열로 → `"ROLE_USER"` |
| `.issuedAt` / `.expiration` | 발급 시각 / 만료 시각. 만료는 **라이브러리가 검증할 때 자동으로 확인**해 준다 |
| `.signWith(key)` | 위조 방지 도장. `key`는 생성자에서 `.env`의 `JWT_SECRET`으로 이미 만들어 뒀다 |
| `.compact()` | 지금까지 조립한 걸 최종 문자열로 뽑아낸다 |

> **왜 `.compact()`가 마지막인가**: 앞의 메서드들은 전부 "설정만 쌓는" 단계이고, 실제로 문자열을 만드는 건 이 호출이다.
> 이런 방식을 빌더 패턴이라 부른다.

---

## 4. `getUserId` 작성하기

```java
public Long getUserId(String token) {
    Claims claims = parse(token);
    return Long.valueOf(claims.getSubject());
}
```

`parse()`는 **클래스 맨 아래에 이미 만들어져 있다.** 그대로 쓰면 된다.
`getSubject()`가 아까 넣은 `String.valueOf(userId)`를 돌려주므로 `Long`으로 되돌린다.

그리고 `parse()` 위에 붙은 `@SuppressWarnings("unused")` 줄은 이제 지워도 된다 (실제로 쓰기 시작했으니까).

---

## 5. `isValid` 작성하기

```java
public boolean isValid(String token) {
    try {
        parse(token);
        return true;
    } catch (JwtException | IllegalArgumentException e) {
        return false;
    }
}
```

**필요한 import 추가** (파일 위쪽에):
```java
import io.jsonwebtoken.JwtException;
```

### 왜 try-catch인가

`parse()`는 문제가 있으면 **예외를 던진다**. 문제 종류가 여러 가지다:

| 상황 | 던지는 예외 |
|---|---|
| 서명이 안 맞음(위조) | `SignatureException` |
| 기한 지남 | `ExpiredJwtException` |
| 형식이 깨짐 | `MalformedJwtException` |
| 빈 문자열 | `IllegalArgumentException` |

앞의 3개는 전부 `JwtException`의 자식이라 **`JwtException` 하나로 잡힌다.**
빈 문자열만 계열이 달라서 `IllegalArgumentException`을 따로 적었다.

> **설계 의도**: 호출하는 쪽(필터)은 "왜 안 되는지"까지 알 필요가 없다. **"쓸 수 있나 없나"만** 알면 된다.
> 그래서 온갖 예외를 `false` 하나로 흡수한다.

---

## 6. 테스트 돌려서 초록 확인

```
.\gradlew.bat test --tests "com.scenelog.auth.JwtProviderTest" --console=plain
```

`BUILD SUCCESSFUL`이 나오면 6개 전부 통과한 것이다. 여기까지가 오늘의 첫 관문.

---

## 7. `AuthService` 작성하기

파일: `src/main/java/com/scenelog/auth/AuthService.java`

```java
@Transactional
public void signup(SignupRequest request) {
    if (userRepository.existsByEmail(request.email())) {
        throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다");
    }

    User user = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .nickname(request.nickname())
            .role(Role.ROLE_USER)
            .build();

    userRepository.save(user);
}

@Transactional(readOnly = true)
public TokenResponse login(LoginRequest request) {
    User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ApiException(
                    HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"));

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다");
    }

    String token = jwtProvider.createToken(user.getUserId(), user.getEmail(), user.getRole());
    return TokenResponse.bearer(token, jwtProvider.getExpirySeconds());
}
```

### 알아둘 것 3가지

**① `passwordEncoder.encode()` — 비밀번호는 절대 그대로 저장하지 않는다**
BCrypt는 되돌릴 수 없는 함수다. DB가 통째로 유출돼도 원래 비밀번호를 알 수 없다.
그래서 나중에 비교할 때도 "복호화해서 비교"가 아니라 `matches(입력값, 저장된해시)`를 쓴다.

**② 두 에러 메시지가 똑같은 게 실수가 아니다** ★ 면접 포인트
"없는 계정"과 "비밀번호 틀림"을 구분해 주면, 공격자가 이메일을 하나씩 넣어보며
**어떤 이메일이 가입돼 있는지 알아낼 수 있다**(계정 열거 취약점). 그래서 일부러 똑같이 응답한다.

**③ `@Transactional`**
`signup`은 DB를 바꾸므로 그냥 `@Transactional`, `login`은 읽기만 하므로 `readOnly = true`.
읽기 전용으로 표시하면 DB가 최적화를 할 수 있다.

---

## 8. `JwtAuthenticationFilter` 작성하기

파일: `src/main/java/com/scenelog/auth/JwtAuthenticationFilter.java`

```java
@Override
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain) throws ServletException, IOException {

    String header = request.getHeader(HEADER);

    if (header != null && header.startsWith(PREFIX)) {
        String token = header.substring(PREFIX.length());

        if (jwtProvider.isValid(token)) {
            Long userId = jwtProvider.getUserId(token);
            userRepository.findById(userId).ifPresent(user -> {
                var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
    }

    filterChain.doFilter(request, response);
}
```

**필요한 import 추가**:
```java
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
```

### 구조가 왜 이런가

**"통과시키는 게 기본, 인증은 덤"**
`if`문이 3중으로 겹쳐 있는데, 어느 조건이든 실패하면 **아무것도 하지 않고 그냥 흘려보낸다.**
여기서 예외를 던지면 안 된다 — 로그인 요청이나 헬스체크처럼 **토큰이 없는 게 정상인 요청**도 이 필터를 지나가기 때문이다.

"권한 없으면 막는" 일은 이 필터가 아니라 **Spring Security가 뒤에서** 한다.
이 필터의 책임은 딱 하나: **"토큰이 있고 유효하면, 누구인지 알려준다."**

**`SecurityContextHolder`** — 이번 요청 동안 "지금 누가 접속했는지"를 담아두는 보관함이다.
여기에 넣어두면 컨트롤러의 `@AuthenticationPrincipal User user`가 꺼내 쓴다.

**`filterChain.doFilter(...)`가 마지막에 있는 이유**
필터는 사슬처럼 줄줄이 연결돼 있고, 이 호출이 "다음 사람에게 넘김"이다.
빠뜨리면 요청이 여기서 멈춰 응답이 영영 안 온다.

---

## 9. 최종 검증 — Swagger에서

앱 실행 후 http://localhost:8080/swagger-ui/index.html

| # | 동작 | 기대 |
|---|---|---|
| 1 | `POST /api/auth/signup` | **201** |
| 2 | 같은 이메일로 또 signup | **409** |
| 3 | `POST /api/auth/login` 올바른 정보 | **200** + accessToken |
| 4 | 틀린 비밀번호로 login | **401** |
| 5 | 우측 상단 **Authorize** → 토큰 붙여넣기 | — |
| 6 | `GET /api/me/history` | **200** (빈 배열) |
| 7 | `POST /api/admin/etl/run` | **403** |

7번까지 되면 **자격요건 6(HTTP·인증/인가) 달성**이다.

> 6번이 403이 나오면 → 필터가 인증 정보를 못 심은 것. `Authorize`에 토큰을 제대로 넣었는지,
> `isValid`가 true를 반환하는지 확인한다.
> 7번이 200이 나오면 → 인가 설정이 잘못된 것. 일반 유저는 관리자 API를 못 써야 정상이다.

---

## 10. 면접 대비 — 이 코드에서 나올 질문 4개

1. **"JWT를 왜 쓰셨나요?"**
   → 서버가 세션을 들고 있지 않아도 되어(무상태), 서버를 늘릴 때 세션 공유 문제가 없습니다.
   다만 토큰을 서버가 강제로 무효화하기 어렵다는 단점이 있어, 만료를 1시간으로 짧게 잡았습니다.

2. **"토큰에 어떤 정보를 담으셨나요?"**
   → userId·email·role만 담았습니다. payload는 암호화가 아니라 인코딩이라 누구나 열어볼 수 있어서,
   민감한 정보는 넣지 않았습니다.

3. **"로그인 실패 메시지가 왜 똑같나요?"**
   → 구분하면 가입된 이메일을 알아낼 수 있어서(계정 열거) 의도적으로 통일했습니다.

4. **"Refresh Token은 왜 없나요?"**
   → 8일이라는 기간 제약에서 우선순위를 판단해 제외했습니다.
   Access Token만 쓰면 만료 시 재로그인해야 하는 불편이 있다는 점은 한계로 인지하고 있습니다.
