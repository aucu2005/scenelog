package com.scenelog.reaction;

import com.scenelog.auth.User;
import com.scenelog.common.error.ApiException;
import com.scenelog.content.WatchSession;
import com.scenelog.content.WatchSessionRepository;
import com.scenelog.reaction.dto.ReactionBatchRequest;
import com.scenelog.reaction.dto.ReactionBatchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 반응 이벤트 수집 (기획서 §6 검증 5종).
 *
 * <p>여기의 소유·정합성 검증이 <b>MongoDB 쪽 참조 정합성의 애플리케이션 방어선</b>이다 —
 * reaction_events의 session_id는 PostgreSQL을 가리키지만 DB가 FK로 보장해 주지 않으므로,
 * 쓰기 시점에 앱이 FK 역할을 대신한다 (기획서 §5.4 대응 1단).
 */
@Service
@RequiredArgsConstructor
public class ReactionService {

    private final WatchSessionRepository sessionRepository;
    private final ReactionEventRepository reactionEventRepository;

    public ReactionBatchResponse registerBatch(User user, Long sessionId, ReactionBatchRequest request) {
        // 검증 1: 세션 존재
        WatchSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다: " + sessionId));

        // 검증 2: 요청자 소유 — 남의 세션에 반응을 심을 수 없다
        if (!session.isOwnedBy(user.getUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 세션이 아닙니다");
        }

        // 검증 3: offset 상한 — 상영시간이 있으면 그 안이어야 한다 (null이면 상한 검사 생략, §5.3)
        Integer durationSec = session.getContent().getDurationSec();
        long contentId = session.getContent().getContentId();

        int inserted = 0;
        int skipped = 0;
        for (ReactionBatchRequest.Item item : request.events()) {
            if (durationSec != null && item.offsetSec() > durationSec) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "offsetSec(%d)이 상영시간(%d초)을 넘습니다".formatted(item.offsetSec(), durationSec));
            }
            try {
                // 검증 5: clientEventId 멱등 — 유니크 인덱스가 중복 삽입을 막는다
                reactionEventRepository.save(new ReactionEvent(
                        item.clientEventId(), sessionId, contentId, user.getUserId(),
                        item.offsetSec(), item.reactionType(), Instant.now()));
                inserted++;
            } catch (DuplicateKeyException e) {
                skipped++;   // 재전송된 이벤트 — 오류가 아니라 정상 (멱등)
            }
        }
        // (검증 4: 배치 ≤ 500은 DTO의 @Size가, enum은 역직렬화가 이미 걸렀다)
        return new ReactionBatchResponse(request.events().size(), inserted, skipped);
    }
}
