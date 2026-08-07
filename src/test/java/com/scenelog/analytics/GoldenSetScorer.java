package com.scenelog.analytics;

import com.scenelog.reaction.sim.Peak;

import java.util.List;

/**
 * 골든셋 채점기 (스펙 §4): 검출 구간과 정답 피크를 겹침(1초 이상)으로 매칭한다.
 *
 * <p>비대칭 규칙: 병합 검출 하나가 정답 두 개를 덮으면 정밀도 쪽 TP는 1, 재현율 쪽 발견은 2다.
 * 사용자 입장에서 "그 구간이 하이라이트"라는 답은 맞으므로 병합에 벌점을 주지 않는다.
 */
final class GoldenSetScorer {

    /** 한 실행의 채점 결과. tpDetections/fp는 검출 기준, foundPeaks/missedPeaks는 정답 기준. */
    record Score(int tpDetections, int fp, int foundPeaks, int missedPeaks) {}

    private GoldenSetScorer() {}

    /** [start, end) 반개구간끼리 1초 이상 공유하면 겹침 */
    private static boolean overlaps(HighlightWindow w, Peak p) {
        return w.startSec() < p.endSec() && p.startSec() < w.endSec();
    }

    static Score score(List<HighlightWindow> detected, List<Peak> answers) {
        int tp = 0, fp = 0;
        for (HighlightWindow w : detected) {
            if (answers.stream().anyMatch(a -> overlaps(w, a))) tp++;
            else fp++;
        }
        int found = 0, missed = 0;
        for (Peak a : answers) {
            if (detected.stream().anyMatch(w -> overlaps(w, a))) found++;
            else missed++;
        }
        return new Score(tp, fp, found, missed);
    }
}
