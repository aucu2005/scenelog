package com.scenelog.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtProvider의 요구사항 정의. 지금은 전부 실패한다 — 통과시키는 것이 오늘의 과제다.
 * 실행: .\gradlew.bat test --tests "com.scenelog.auth.JwtProviderTest" --console=plain
 */
class JwtProviderTest {

    // 테스트 전용 시크릿(32바이트 이상). 실제 값은 .env에서 주입되므로 여기 값은 아무 의미 없다.
    private static final String SECRET = "test-secret-key-for-jwt-provider-unit-test-0123456789";

    private final JwtProvider provider = new JwtProvider(SECRET, 3600);

    @Test
    void 발급한_토큰에서_userId를_다시_꺼낼_수_있다() {
        String token = provider.createToken(42L, "user@example.com", Role.ROLE_USER);

        assertThat(token).isNotBlank();
        assertThat(provider.getUserId(token)).isEqualTo(42L);
    }

    @Test
    void 정상_토큰은_유효하다() {
        String token = provider.createToken(1L, "a@b.com", Role.ROLE_ADMIN);

        assertThat(provider.isValid(token)).isTrue();
    }

    @Test
    void 변조된_토큰은_유효하지_않다() {
        String token = provider.createToken(1L, "a@b.com", Role.ROLE_USER);
        String tampered = token.substring(0, token.length() - 3) + "xyz";   // 서명 부분을 깨뜨린다

        assertThat(provider.isValid(tampered)).isFalse();
    }

    @Test
    void 형식이_아닌_문자열은_예외가_아니라_false다() {
        assertThat(provider.isValid("this-is-not-a-jwt")).isFalse();
        assertThat(provider.isValid("")).isFalse();
    }

    @Test
    void 만료된_토큰은_유효하지_않다() {
        JwtProvider expired = new JwtProvider(SECRET, -60);   // 이미 지난 만료시각으로 발급
        String token = expired.createToken(1L, "a@b.com", Role.ROLE_USER);

        assertThat(expired.isValid(token)).isFalse();
    }

    @Test
    void 다른_시크릿으로_만든_토큰은_유효하지_않다() {
        JwtProvider other = new JwtProvider("completely-different-secret-key-0123456789abcdef", 3600);
        String foreignToken = other.createToken(1L, "a@b.com", Role.ROLE_USER);

        assertThat(provider.isValid(foreignToken)).isFalse();
    }
}
