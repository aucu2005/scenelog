package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactionSimulatorTest {

    private final ReactionSimulator simulator = new ReactionSimulator();

    // 인셉션 각본: 2400~2450 TENSION, 6100~6160 TOUCHED (기획서 §5-B-1)
    private final Scenario inception = new Scenario(1L, 8880, 0.5, List.of(
            new Peak(2400, 2450, ReactionType.TENSION, 15.0),
            new Peak(6100, 6160, ReactionType.TOUCHED, 12.0)
    ));

    @Test
    void 모든_이벤트의_offset은_상영시간_안에_있다() {
        List<ReactionEvent> events = simulator.generate(inception, 20, 42L);
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.offsetSec()).isBetween(0, 8879);
            assertThat(e.contentId()).isEqualTo(1L);
        });
    }

    @Test
    void 같은_seed는_같은_결과를_낸다() {  // 결정적이어야 테스트와 시연이 재현 가능하다
        List<ReactionEvent> a = simulator.generate(inception, 20, 42L);
        List<ReactionEvent> b = simulator.generate(inception, 20, 42L);
        assertThat(a).hasSameSizeAs(b);
        assertThat(a.get(0).offsetSec()).isEqualTo(b.get(0).offsetSec());
    }

    @Test
    void 피크_구간의_초당_반응밀도는_바깥의_3배를_넘는다() {  // 노이즈 위에 정답이 실제로 솟아있는지
        List<ReactionEvent> events = simulator.generate(inception, 50, 42L);
        long inPeak = events.stream()
                .filter(e -> e.offsetSec() >= 2400 && e.offsetSec() < 2450).count();
        long outside = events.stream()
                .filter(e -> e.offsetSec() >= 3000 && e.offsetSec() < 3050).count();
        double inDensity = inPeak / 50.0;
        double outDensity = Math.max(outside, 1) / 50.0;
        assertThat(inDensity / outDensity).isGreaterThan(3.0);
    }

    @Test
    void client_event_id는_전부_유일하다() {  // 멱등키의 전제 (§5.2)
        List<ReactionEvent> events = simulator.generate(inception, 20, 42L);
        long distinct = events.stream().map(ReactionEvent::clientEventId).distinct().count();
        assertThat(distinct).isEqualTo(events.size());
    }
}
