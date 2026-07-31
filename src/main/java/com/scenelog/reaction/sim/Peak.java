package com.scenelog.reaction.sim;

import com.scenelog.reaction.ReactionType;

/** 각본에 심는 정답 피크: [startSec, endSec) 구간에서 발생 확률이 multiplier배가 된다 (기획서 §5-B-1) */
public record Peak(int startSec, int endSec, ReactionType type, double multiplier) {

    public boolean contains(int sec) {
        return sec >= startSec && sec < endSec;
    }
}
