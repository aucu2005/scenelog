package com.scenelog.analytics;

import com.scenelog.reaction.ReactionType;

/** 한 버킷의 유형별 카운트 — segment_stats 한 행에 대응 (기획서 §5.1) */
public record BucketCounts(int laugh, int tension, int touched, int bored) {

    public static final BucketCounts ZERO = new BucketCounts(0, 0, 0, 0);

    public int total() {
        return laugh + tension + touched + bored;
    }

    public BucketCounts plus(ReactionType type) {
        return switch (type) {
            case LAUGH   -> new BucketCounts(laugh + 1, tension, touched, bored);
            case TENSION -> new BucketCounts(laugh, tension + 1, touched, bored);
            case TOUCHED -> new BucketCounts(laugh, tension, touched + 1, bored);
            case BORED   -> new BucketCounts(laugh, tension, touched, bored + 1);
        };
    }
}
