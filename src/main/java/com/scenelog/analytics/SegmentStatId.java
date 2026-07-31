package com.scenelog.analytics;

import java.io.Serializable;
import java.util.Objects;

/** segment_stats 복합 PK (content_id, bucket_start_sec) — @IdClass 용 */
public class SegmentStatId implements Serializable {

    private Long contentId;
    private Integer bucketStartSec;

    public SegmentStatId() {}

    public SegmentStatId(Long contentId, Integer bucketStartSec) {
        this.contentId = contentId;
        this.bucketStartSec = bucketStartSec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SegmentStatId that)) return false;
        return Objects.equals(contentId, that.contentId)
                && Objects.equals(bucketStartSec, that.bucketStartSec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentId, bucketStartSec);
    }
}
