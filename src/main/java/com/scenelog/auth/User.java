package com.scenelog.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 회원 (기획서 §5.1 users) */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용 기본 생성자 — 외부에서 못 쓰게 막는다
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // PostgreSQL BIGSERIAL
    private Long userId;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /**
     * BCrypt 해시.
     * DelegatingPasswordEncoder가 "{bcrypt}$2a$10$..." 형태로 접두어를 붙여 68자를 저장하므로
     * 60자로 잡으면 회원가입에서 즉시 실패한다 → 100자 (기획서 §5.1의 v4 버그 수정 항목)
     */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private OffsetDateTime createdAt;   // TIMESTAMPTZ

    @Builder
    private User(String email, String passwordHash, String nickname, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = (role == null) ? Role.ROLE_USER : role;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
