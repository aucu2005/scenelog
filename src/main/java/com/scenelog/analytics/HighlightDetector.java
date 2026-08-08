package com.scenelog.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

/**
 * 버킷 밀도 → 중심 이동평균 → z-score 피크 (기획서 §7, method=ZSCORE_V2 (minLift=0이면 v1 동작)).
 *
 * <p>"AI"가 아니라 통계다: 평균에서 몇 표준편차나 벗어났는가(z-score)로 "유별난 구간"을 찾는다.
 * 상수 시작값은 여기 고정하고, EDA(day7) 결과로 조정할 때는 근거를 문서화한다.
 */
public class HighlightDetector {

    static final int MOVING_AVG_WINDOW = 3;   // 중심 이동평균 창 (홀수)
    public static final double Z_THRESHOLD = 2.0;
    static final int MIN_BUCKETS = 6;         // 이하면 통계가 무의미 → 빈 결과

    /**
     * 절대 하한 배수 — 스무딩 값이 (전체 평균 × minLift) 이상이어야 하이라이트 후보다.
     * 골든셋 실측(2026-08-07)에서 FP 398건이 무피크·약한 신호 콘텐츠에 집중된 원인이
     * z-score의 상대성(절대 높이 하한 부재)으로 규명되어 도입 — 스펙 detector-v2-min-lift.
     * 0이면 하한 비활성 = v1(ZSCORE_V1)과 동일 동작.
     * 값은 튜닝 시드(1~10) 스윕에서 "재현율 ≥97%(발견 ≥165/170) 유지 중 정밀도 최대" 기준으로 선정 =
     * k=2.5 (정밀도 98.0%, 발견 167/170) — build/reports/min-lift-sweep.md.
     */
    public static final double DEFAULT_MIN_LIFT = 2.5;

    private final double minLift;

    public HighlightDetector() {
        this(DEFAULT_MIN_LIFT);
    }

    public HighlightDetector(double minLift) {
        this.minLift = minLift;
    }

    public List<HighlightWindow> detect(SortedMap<Integer, Integer> totalsByBucket, int bucketSizeSec) {
        int n = totalsByBucket.size();
        if (n < MIN_BUCKETS) return List.of();

        int[] starts = new int[n];
        double[] values = new double[n];
        int i = 0;
        for (var e : totalsByBucket.entrySet()) {
            starts[i] = e.getKey();
            values[i] = e.getValue();
            i++;
        }

        // 1) 중심 이동평균 — 한 버킷짜리 우연한 튐(노이즈)을 완만하게 만든다
        double[] smoothed = new double[n];
        int half = MOVING_AVG_WINDOW / 2;
        for (int k = 0; k < n; k++) {
            int from = Math.max(0, k - half), to = Math.min(n - 1, k + half);
            double sum = 0;
            for (int j = from; j <= to; j++) sum += values[j];
            smoothed[k] = sum / (to - from + 1);
        }

        // 2) 평균·표준편차
        double mean = 0;
        for (double v : smoothed) mean += v;
        mean /= n;
        double var = 0;
        for (double v : smoothed) var += (v - mean) * (v - mean);
        double std = Math.sqrt(var / n);
        if (std == 0) return List.of();   // 완전 평탄 — 피크 없음

        // 3) z ≥ 임계값인 연속 버킷을 하나의 구간으로 병합
        List<HighlightWindow> result = new ArrayList<>();
        int windowStart = -1;
        double windowMaxZ = 0;
        for (int k = 0; k < n; k++) {
            double z = (smoothed[k] - mean) / std;
            if (z >= Z_THRESHOLD && smoothed[k] >= mean * minLift) {
                if (windowStart < 0) { windowStart = starts[k]; windowMaxZ = z; }
                else windowMaxZ = Math.max(windowMaxZ, z);
            } else if (windowStart >= 0) {
                result.add(new HighlightWindow(windowStart, starts[k - 1] + bucketSizeSec, windowMaxZ));
                windowStart = -1;
            }
        }
        if (windowStart >= 0) {
            result.add(new HighlightWindow(windowStart, starts[n - 1] + bucketSizeSec, windowMaxZ));
        }
        return result;
    }
}
