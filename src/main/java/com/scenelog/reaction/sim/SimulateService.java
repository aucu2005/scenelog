package com.scenelog.reaction.sim;

import com.scenelog.auth.User;
import com.scenelog.common.error.ApiException;
import com.scenelog.content.Content;
import com.scenelog.content.ContentRepository;
import com.scenelog.content.WatchSession;
import com.scenelog.content.WatchSessionRepository;
import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionService;
import com.scenelog.reaction.dto.ReactionBatchRequest;
import com.scenelog.reaction.dto.ReactionBatchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시연 모드 (기획서 §5-B-4): 시뮬레이터가 만든 이벤트를 <b>실제 API 경로와 같은 서비스</b>로 등록한다.
 *
 * <p>DB에 직접 넣지 않는 이유: {@code ReactionService.registerBatch}를 통과해야
 * 소유 검증·offset 범위·배치 상한·멱등이 <b>실제로 도는지</b> 함께 확인되기 때문이다.
 * 시연이 곧 검증 로직의 통합 테스트다.
 *
 * <p>멱등: clientEventId가 (contentId, u, seq)로 결정적이라 재실행 시 inserted=0이 된다.
 * 단, 시청 세션은 실행마다 새로 만들어진다 — 세션은 "시청 1회"를 의미하므로 수용 가능한 트레이드오프.
 */
@Service
@RequiredArgsConstructor
public class SimulateService {

    /** durationSec이 null인 콘텐츠의 기본 상영시간 (2시간) — §5.3 nullable 대응 */
    static final int FALLBACK_DURATION_SEC = 7200;
    private static final int BATCH_LIMIT = 500;

    private final ContentRepository contentRepository;
    private final WatchSessionRepository sessionRepository;
    private final ReactionService reactionService;

    private final ReactionSimulator simulator = new ReactionSimulator();

    public Map<String, Object> simulate(User caller, long contentId, int users, long seed) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다: " + contentId));

        int duration = (content.getDurationSec() != null) ? content.getDurationSec() : FALLBACK_DURATION_SEC;
        Scenario scenario = Scenario.defaultFor(contentId, duration);
        List<ReactionEvent> events = simulator.generate(scenario, users, seed);

        // 합성 사용자 u → 실제 시청 세션 (caller 소유 — 소유 검증을 통과하는 유일한 방법)
        Map<Long, WatchSession> sessionByUser = new LinkedHashMap<>();
        for (long u = 1; u <= users; u++) {
            sessionByUser.put(u, sessionRepository.save(
                    WatchSession.builder().user(caller).content(content).build()));
        }

        int inserted = 0;
        int skipped = 0;
        for (Map.Entry<Long, WatchSession> entry : sessionByUser.entrySet()) {
            long syntheticUser = entry.getKey();
            Long realSessionId = entry.getValue().getSessionId();

            List<ReactionBatchRequest.Item> items = events.stream()
                    .filter(e -> e.userId() == syntheticUser)
                    .map(e -> new ReactionBatchRequest.Item(e.clientEventId(), e.offsetSec(), e.reactionType()))
                    .toList();

            for (int from = 0; from < items.size(); from += BATCH_LIMIT) {
                List<ReactionBatchRequest.Item> chunk =
                        items.subList(from, Math.min(from + BATCH_LIMIT, items.size()));
                ReactionBatchResponse r = reactionService.registerBatch(
                        caller, realSessionId, new ReactionBatchRequest(new ArrayList<>(chunk)));
                inserted += r.inserted();
                skipped += r.skippedDuplicate();
            }
        }

        // peaks를 응답에 포함한다 — 이것이 day4 검출 정확도 측정의 "정답지"다 (§5-B-5)
        return Map.of(
                "contentId", contentId,
                "durationSec", duration,
                "users", users,
                "seed", seed,
                "sessionsCreated", sessionByUser.size(),
                "generated", events.size(),
                "inserted", inserted,
                "skippedDuplicate", skipped,
                "answerPeaks", scenario.peaks());
    }
}
