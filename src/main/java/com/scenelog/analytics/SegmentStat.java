package com.scenelog.analytics;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 10초 버킷 집계 결과 — 집계 배치만 쓴다 (기획서 §5.1, 쓰기 소유권 분리).
 * 원본(reaction_events, fact)과 집계(segment_stats, mart)의 분리 — 데이터 마트의 축소판.
 */
@Entity
@Table(name = "segment_stats")
@IdClass(SegmentStatId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SegmentStat {

    @Id
    private Long contentId;

    @Id
    private Integer bucketStartSec;

    private int laughCnt;
    private int tensionCnt;
    private int touchedCnt;
    private int boredCnt;
    private int totalCount;
    private OffsetDateTime updatedAt;

    public SegmentStat(Long contentId, Integer bucketStartSec, BucketCounts c) {
        this.contentId = contentId;
        this.bucketStartSec = bucketStartSec;
        this.laughCnt = c.laugh();
        this.tensionCnt = c.tension();
        this.touchedCnt = c.touched();
        this.boredCnt = c.bored();
        this.totalCount = c.total();
        this.updatedAt = OffsetDateTime.now();
    }
}
