package com.scenelog.etl;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * 검증에서 탈락한 레코드를 <b>버리지 않고 격리</b>한다 (기획서 §5-A-2).
 *
 * <p>대부분의 학습용 프로젝트는 실패 데이터를 try-catch로 삼켜 없애버린다.
 * 실무와 갈리는 지점이 여기다 — 데이터 품질 관리의 실질은 카운트가 아니라
 * <b>격리와 재처리</b>다. 이 컬렉션이 있어야 "왜 실패했는지" 사후 분석이 가능하다.
 */
@Document(collection = "rejected_records")
public class RejectedRecord {

    @Id
    private String id;

    private String source;          // "TMDB"
    private Integer tmdbId;         // null일 수 있다 (id 자체가 없어서 탈락한 경우)
    private Map<String, Object> payload;

    /** MISSING_FIELD:id | MISSING_FIELD:title | INTEGRITY_FAIL:runtime | INTEGRITY_FAIL:release_date */
    private String rejectReason;

    private Instant rejectedAt;

    /** 재처리 완료 시각. null이면 아직 대기 중 */
    private Instant reprocessedAt;

    protected RejectedRecord() {}

    public RejectedRecord(String source, Integer tmdbId, Map<String, Object> payload, String rejectReason) {
        this.source = source;
        this.tmdbId = tmdbId;
        this.payload = payload;
        this.rejectReason = rejectReason;
        this.rejectedAt = Instant.now();
    }

    public void markReprocessed() {
        this.reprocessedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getSource() { return source; }
    public Integer getTmdbId() { return tmdbId; }
    public Map<String, Object> getPayload() { return payload; }
    public String getRejectReason() { return rejectReason; }
    public Instant getRejectedAt() { return rejectedAt; }
    public Instant getReprocessedAt() { return reprocessedAt; }
}
