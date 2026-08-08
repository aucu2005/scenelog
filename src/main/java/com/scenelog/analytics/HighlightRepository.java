package com.scenelog.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface HighlightRepository extends JpaRepository<Highlight, Long> {

    /** 현행 방식의 결과만 조회 — 버저닝은 쓰기만이 아니라 읽기도 현행 method를 지정해야 완성된다 */
    List<Highlight> findByContentIdAndMethodOrderByStartSec(Long contentId, String method);

    /** 같은 방식(method)의 이전 결과만 지운다 — 다른 알고리즘의 이력은 보존 (버저닝) */
    @Modifying
    @Query("delete from Highlight h where h.contentId = :contentId and h.method = :method")
    void deleteAllByContentIdAndMethod(Long contentId, String method);
}
