package com.scenelog.reaction;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 반응 이벤트 원본 — append-only (기획서 §5.2 reaction_events).
 *
 * <p>설계 노트: core-five 계획에서는 record로 정의했지만, Mongo 저장에는 {@code @Id} 필드가
 * 필요하고 record는 컴포넌트 외 필드를 가질 수 없다. 그래서 클래스로 바꾸되
 * <b>접근자를 record 스타일</b>({@code offsetSec()}, {@code clientEventId()})로 유지해
 * 계획서의 테스트·집계 코드가 그대로 동작한다.
 *
 * <p>인덱스는 {@code MongoIndexConfig}가 기동 시 명시적으로 생성한다
 * (자동 생성 프로퍼티는 Boot 4 프리픽스 이동 이슈가 있어 코드로 못박는다 — 트러블슈팅 3호).
 */
@Document(collection = "reaction_events")
@CompoundIndex(name = "content_offset_idx", def = "{'contentId': 1, 'offsetSec': 1}")
public class ReactionEvent {

    @Id
    private String id;

    /** 멱등키 — 유니크 인덱스. 같은 배치가 두 번 와도 중복이 쌓이지 않게 한다 */
    private String clientEventId;

    private long sessionId;    // PostgreSQL watch_sessions 참조 — DB가 보장하지 않으므로 앱이 검증 (§5.4)
    private long contentId;
    private long userId;
    private int offsetSec;     // 재생 시작(0) 기준 경과 초
    private ReactionType reactionType;
    private Instant createdAt; // 서버 수신 시각 (offsetSec과 별개)

    protected ReactionEvent() {}

    public ReactionEvent(String clientEventId, long sessionId, long contentId, long userId,
                         int offsetSec, ReactionType reactionType, Instant createdAt) {
        this.clientEventId = clientEventId;
        this.sessionId = sessionId;
        this.contentId = contentId;
        this.userId = userId;
        this.offsetSec = offsetSec;
        this.reactionType = reactionType;
        this.createdAt = createdAt;
    }

    // record 스타일 접근자 — 계획서의 테스트 코드와 호환
    public String id() { return id; }
    public String clientEventId() { return clientEventId; }
    public long sessionId() { return sessionId; }
    public long contentId() { return contentId; }
    public long userId() { return userId; }
    public int offsetSec() { return offsetSec; }
    public ReactionType reactionType() { return reactionType; }
    public Instant createdAt() { return createdAt; }
}
