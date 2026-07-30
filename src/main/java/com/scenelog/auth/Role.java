package com.scenelog.auth;

/**
 * 권한. Spring Security의 hasRole("ADMIN")은 "ROLE_" 접두어를 자동으로 붙여 비교하므로
 * enum 이름에 접두어를 포함한다 (기획서 §5.1 users.role).
 */
public enum Role {
    ROLE_USER,
    ROLE_ADMIN
}
