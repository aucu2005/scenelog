package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRunnerTest {

    private final ReactionSimulator simulator = new ReactionSimulator();

    @Test
    void 시드_각본은_상영시간과_무관하게_세션당_약_100건을_만든다() {
        // 상영시간이 2배면 baseline을 절반으로 — 총량이 콘텐츠 길이에 휘둘리면 100만 건 계산이 틀어진다
        for (int duration : new int[]{5400, 7200, 10800}) {
            List<ReactionEvent> events = simulator.generate(
                    SeedRunner.seedScenario(2L, duration), 10, 42L);
            // 기대값: 10명 × (기본 100 + 피크 가산 ~20) — 고정 seed라 결과는 결정적이다
            assertThat(events.size())
                    .as("duration=%d", duration)
                    .isBetween(900, 1500);
        }
    }

    @Test
    void 시드_각본에도_피크가_있다() {  // 시드 데이터도 시연 가능해야 한다 (하이라이트 검출 대상)
        Scenario scenario = SeedRunner.seedScenario(2L, 7200);
        assertThat(scenario.peaks()).hasSize(2);
        assertThat(scenario.contentId()).isEqualTo(2L);
    }

    @Test
    void 변환된_이벤트는_seed_프리픽스와_실제_세션을_가진다() {
        // 시연 데이터(sim-)와 clientEventId가 충돌하면 유니크 인덱스가 시드를 막아버린다
        ReactionEvent sim = new ReactionEvent(
                "sim-c2-u7-31", 7L, 2L, 7L, 1234, ReactionType.TENSION, Instant.EPOCH);
        Instant now = Instant.parse("2026-08-01T12:00:00Z");

        ReactionEvent seeded = SeedRunner.toSeedEvent(sim, 5001L, 42L, now);

        assertThat(seeded.clientEventId()).isEqualTo("seed-c2-u7-31");
        assertThat(seeded.sessionId()).isEqualTo(5001L);   // 합성 u가 아니라 PostgreSQL 실제 세션
        assertThat(seeded.userId()).isEqualTo(42L);        // 세션 소유자 — §5.4 정합성 유지
        assertThat(seeded.offsetSec()).isEqualTo(1234);
        assertThat(seeded.reactionType()).isEqualTo(ReactionType.TENSION);
        assertThat(seeded.createdAt()).isEqualTo(now);
    }
}
