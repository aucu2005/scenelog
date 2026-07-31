package com.scenelog.analytics.dto;

import com.scenelog.analytics.SegmentStat;

import java.io.Serializable;

/** 타임라인 한 버킷. Redis 캐시에 JSON으로 직렬화되므로 Serializable 표시 */
public record TimelineBucketResponse(
        int bucketStartSec,
        int laugh,
        int tension,
        int touched,
        int bored,
        int total
) implements Serializable {

    public static TimelineBucketResponse from(SegmentStat s) {
        return new TimelineBucketResponse(s.getBucketStartSec(),
                s.getLaughCnt(), s.getTensionCnt(), s.getTouchedCnt(), s.getBoredCnt(), s.getTotalCount());
    }
}
