package com.scenelog.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * ★ 직접 구현 대상 1/3 — JWT 발급·검증.
 *
 * <p>테스트가 요구사항을 정의한다: {@code src/test/java/com/scenelog/auth/JwtProviderTest.java}
 * <br>실행: {@code .\gradlew.bat test --tests "com.scenelog.auth.JwtProviderTest" --console=plain}
 *
 * <p>jjwt 0.12.x API 요약:
 * <pre>
 *   발급: Jwts.builder()
 *              .subject(String)             // 토큰 주체 — userId를 문자열로
 *              .claim("email", email)       // 임의 클레임 추가
 *              .issuedAt(new Date())
 *              .expiration(new Date(...))
 *              .signWith(key)
 *              .compact();                  // → String
 *
 *   검증: Claims claims = Jwts.parser()
 *              .verifyWith(key)
 *              .build()
 *              .parseSignedClaims(token)    // 서명·만료 불일치 시 JwtException 발생
 *              .getPayload();
 *         claims.getSubject(), claims.get("role", String.class)
 * </pre>
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiry-seconds}") long expirySeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {   // HS256은 최소 256비트 — 짧으면 기동 시점에 명확히 실패시킨다
            throw new IllegalStateException(
                    "jwt.secret이 너무 짧습니다(%d바이트). 32바이트 이상 필요 — .env의 JWT_SECRET을 확인하세요."
                            .formatted(bytes.length));
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirySeconds = expirySeconds;
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }

    /**
     * 토큰을 발급한다.
     *
     * <p>TODO(직접 구현):
     * <ol>
     *   <li>{@code now = new Date()}, {@code exp = new Date(now.getTime() + expirySeconds * 1000)}</li>
     *   <li>subject에 {@code String.valueOf(userId)}</li>
     *   <li>claim "email"에 email, claim "role"에 {@code role.name()}</li>
     *   <li>issuedAt(now), expiration(exp), signWith(key) 후 compact() 반환</li>
     * </ol>
     */
    public String createToken(Long userId, String email, Role role) {
        throw new UnsupportedOperationException("TODO: 직접 구현 — JwtProviderTest를 통과시키세요");
    }

    /**
     * 토큰에서 userId를 꺼낸다. 유효하지 않으면 예외가 나가도 된다(호출부가 isValid로 먼저 거른다).
     *
     * <p>TODO(직접 구현): parseSignedClaims로 Claims를 얻고 {@code Long.valueOf(claims.getSubject())} 반환
     */
    public Long getUserId(String token) {
        throw new UnsupportedOperationException("TODO: 직접 구현 — JwtProviderTest를 통과시키세요");
    }

    /**
     * 서명이 맞고 만료되지 않았으면 true.
     *
     * <p>TODO(직접 구현): parseSignedClaims를 try에 넣고, {@code catch (JwtException | IllegalArgumentException e)}
     * 에서 false를 반환한다. 변조·만료·형식오류를 모두 false로 흡수하는 것이 목적이다.
     */
    public boolean isValid(String token) {
        throw new UnsupportedOperationException("TODO: 직접 구현 — JwtProviderTest를 통과시키세요");
    }

    /** 위 메서드 구현 시 참고용 — 필요 없으면 지워도 된다. */
    @SuppressWarnings("unused")
    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
