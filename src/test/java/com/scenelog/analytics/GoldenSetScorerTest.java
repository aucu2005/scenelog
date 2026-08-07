package com.scenelog.analytics;

import com.scenelog.reaction.ReactionType;
import com.scenelog.reaction.sim.Peak;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.scenelog.analytics.GoldenSetScorer.Score;
import static com.scenelog.analytics.GoldenSetScorer.score;
import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetScorerTest {

    private static Peak peak(int start, int end) {
        return new Peak(start, end, ReactionType.TENSION, 15.0);
    }

    @Test
    void 겹치는_검출은_TP이고_그_정답은_발견이다() {
        var detected = List.of(new HighlightWindow(100, 130, 3.0));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(1, 0, 1, 0));
    }

    @Test
    void 어떤_정답과도_안_겹치는_검출은_FP이고_그_정답은_FN이다() {
        var detected = List.of(new HighlightWindow(500, 520, 2.5));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(0, 1, 0, 1));
    }

    @Test
    void 경계가_맞닿기만_하면_겹침이_아니다() {   // [100,120) vs [120,170) — 공유 구간 0초
        var detected = List.of(new HighlightWindow(100, 120, 2.5));
        var answers = List.of(peak(120, 170));
        assertThat(score(detected, answers)).isEqualTo(new Score(0, 1, 0, 1));
    }

    @Test
    void 병합_검출_하나가_정답_둘을_덮으면_TP1_발견2다() {   // 스펙 §4 비대칭 규칙
        var detected = List.of(new HighlightWindow(3000, 3130, 4.0));
        var answers = List.of(peak(3000, 3050), peak(3080, 3130));
        assertThat(score(detected, answers)).isEqualTo(new Score(1, 0, 2, 0));
    }

    @Test
    void 무피크_콘텐츠에서_검출이_없으면_전부_0이다() {
        assertThat(score(List.of(), List.of())).isEqualTo(new Score(0, 0, 0, 0));
    }
}
