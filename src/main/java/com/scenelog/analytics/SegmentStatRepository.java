package com.scenelog.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SegmentStatRepository extends JpaRepository<SegmentStat, SegmentStatId> {

    List<SegmentStat> findByContentIdOrderByBucketStartSec(Long contentId);

    /** 전량 재계산의 "지우기" 절반 — 벌크 삭제 (개별 로드 없이 한 방) */
    @Modifying
    @Query("delete from SegmentStat s where s.contentId = :contentId")
    void deleteAllByContentId(Long contentId);
}
