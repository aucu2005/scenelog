package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 각본대로 반응 이벤트를 생성한다 (기획서 §5-B-2).
 *
 * <p>순수 생성 로직 — Spring·DB 비의존. REST 전송(시연 모드)과 벌크 적재(시드 모드, day5)가
 * 각자 이 결과를 소비한다.
 *
 * <p>clientEventId는 (contentId, 사용자 순번, 순번)으로 <b>결정적</b>이다 —
 * 같은 파라미터로 재실행하면 같은 id가 나와서, 유니크 인덱스가 중복 적재를 막는다(멱등).
 */
public class ReactionSimulator {

    /** 피크 구간 안에서 피크 타입이 아닌 다른 반응이 섞이는 비율 — 현실의 잡음 재현 */
    static final double OFF_TYPE_RATIO = 0.2;

    public List<ReactionEvent> generate(Scenario scenario, int userCount, long seed) {
        Random rnd = new Random(seed);
        List<ReactionEvent> events = new ArrayList<>();
        ReactionType[] types = ReactionType.values();

        for (int u = 1; u <= userCount; u++) {
            long syntheticSessionId = u;   // 합성 ID — 시연 모드가 실제 세션 ID로 치환한다
            int seq = 0;
            for (int sec = 0; sec < scenario.durationSec(); sec++) {
                double p = scenario.baselinePerMinute() / 60.0;
                Peak active = null;
                for (Peak peak : scenario.peaks()) {
                    if (peak.contains(sec)) { active = peak; break; }
                }
                if (active != null) p *= active.multiplier();

                if (rnd.nextDouble() >= p) continue;

                ReactionType type;
                if (active != null && rnd.nextDouble() >= OFF_TYPE_RATIO) {
                    type = active.type();
                } else {
                    type = types[rnd.nextInt(types.length)];
                }
                events.add(new ReactionEvent(
                        "sim-c%d-u%d-%d".formatted(scenario.contentId(), u, seq++),
                        syntheticSessionId, scenario.contentId(), u, sec, type, Instant.EPOCH));
            }
        }
        return events;
    }
}
