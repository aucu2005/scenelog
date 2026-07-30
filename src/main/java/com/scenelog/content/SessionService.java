package com.scenelog.content;

import com.scenelog.auth.User;
import com.scenelog.common.error.ApiException;
import com.scenelog.content.dto.SessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionService {

    private final WatchSessionRepository sessionRepository;
    private final ContentRepository contentRepository;

    @Transactional
    public SessionResponse start(User user, Long contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다: " + contentId));
        WatchSession session = WatchSession.builder().user(user).content(content).build();
        return SessionResponse.from(sessionRepository.save(session));
    }

    /**
     * 세션 종료.
     * 소유자 검증이 핵심 — 남의 세션을 조작할 수 없어야 한다.
     * 반응 등록(8/2)도 같은 검증을 재사용하며, 이것이 MongoDB 쪽 참조 정합성의
     * 애플리케이션 방어선이다 (기획서 §5.4 대응 1단).
     */
    @Transactional
    public SessionResponse end(User user, Long sessionId) {
        WatchSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다: " + sessionId));
        if (!session.isOwnedBy(user.getUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 세션이 아닙니다");
        }
        session.end();
        return SessionResponse.from(session);
    }

    public List<SessionResponse> myHistory(User user) {
        return sessionRepository.findByUser_UserIdOrderByStartedAtDesc(user.getUserId())
                .stream().map(SessionResponse::from).toList();
    }
}
