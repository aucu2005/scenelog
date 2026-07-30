package com.scenelog.auth.dto;

/**
 * Access Token 단독 응답.
 * Refresh Token은 의도적으로 미구현 — 근거는 README "기술적 판단" 절에 기록한다 (기획서 §6).
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
