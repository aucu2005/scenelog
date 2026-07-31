package com.scenelog.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface HighlightRepository extends JpaRepository<Highlight, Long> {

    List<Highlight> findByContentIdOrderByStartSec(Long contentId);

    /** 같은 방식(method)의 이전 결과만 지운다 — 다른 알고리즘의 이력은 보존 (버저닝) */
    @Modifying
    @Query("delete from Highlight h where h.contentId = :contentId and h.method = :method")
    void deleteAllByContentIdAndMethod(Long contentId, String method);
}
