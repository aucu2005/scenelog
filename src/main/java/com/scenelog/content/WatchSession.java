package com.scenelog.content;

import com.scenelog.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 시청 세션 (기획서 §5.1 watch_sessions).
 *
 * <p>여기서 PostgreSQL은 FK로 참조 정합성을 <b>보장</b>한다.
 * 반면 MongoDB의 reaction_events는 이 session_id를 가리키면서도 DB가 보장해 주지 않는다
 * — 그 공백을 애플리케이션 검증 + 고아 검출 배치로 메우는 것이 기획서 §5.4의 설계다.
 */
@Entity
@Table(name = "watch_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    /** 진행 중이면 null */
    private OffsetDateTime endedAt;

    @Builder
    private WatchSession(User user, Content content) {
        this.user = user;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        this.startedAt = OffsetDateTime.now();
    }

    public void end() {
        this.endedAt = OffsetDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return this.user.getUserId().equals(userId);
    }
}
