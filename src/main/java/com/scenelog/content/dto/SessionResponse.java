package com.scenelog.content.dto;

import com.scenelog.content.WatchSession;

import java.time.OffsetDateTime;

public record SessionResponse(
        Long sessionId,
        Long contentId,
        String contentTitle,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {
    public static SessionResponse from(WatchSession s) {
        return new SessionResponse(s.getSessionId(), s.getContent().getContentId(),
                s.getContent().getTitle(), s.getStartedAt(), s.getEndedAt());
    }
}
