package com.scenelog.reaction.sim;

import java.util.List;

/**
 * 시뮬레이터 각본 = 정답지 (기획서 §5-B-1).
 * peaks가 곧 하이라이트 검출의 기대 정답이며, 검출 정확도 테스트(day4)가 재사용한다.
 */
public record Scenario(
        long contentId,
        int durationSec,
        double baselinePerMinute,   // 배경 노이즈: 사용자당 분당 반응 횟수
        List<Peak> peaks
) {
    /**
     * 상영시간 기준 상대 위치에 정답 피크 2개를 심는 기본 각본.
     * 27% 지점 TENSION, 68% 지점 TOUCHED — 콘텐츠 길이에 관계없이 같은 구조가 나온다.
     */
    public static Scenario defaultFor(long contentId, int durationSec) {
        int p1 = (int) (durationSec * 0.27);
        int p2 = (int) (durationSec * 0.68);
        return new Scenario(contentId, durationSec, 0.5, List.of(
                new Peak(p1, p1 + 50, com.scenelog.reaction.ReactionType.TENSION, 15.0),
                new Peak(p2, p2 + 60, com.scenelog.reaction.ReactionType.TOUCHED, 12.0)
        ));
    }
}
