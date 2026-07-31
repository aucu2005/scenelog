package com.scenelog.etl;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * TMDB 응답 원본을 손대지 않고 보관한다 (기획서 §5-A-1).
 *
 * <p><b>왜 원본을 따로 두는가</b>: 나중에 "장르도 저장할까?"처럼 정제 규칙이 바뀌면,
 * API를 500번 다시 호출하는 대신 여기서 재처리하면 된다.
 * 수집(비싸고 느림)과 정제(싸고 빠름)를 분리하는 것이 ETL의 기본이다.
 */
@Document(collection = "raw_content")
public class RawContent {

    @Id
    private String id;

    @Indexed(unique = true)
    private Integer tmdbId;

    private Instant fetchedAt;

    /** TMDB JSON 전체. credits·keywords 포함 (지금은 안 쓰지만 나중에 다시 못 받는다 — §13-7) */
    private Map<String, Object> payload;

    protected RawContent() {}

    public RawContent(Integer tmdbId, Map<String, Object> payload) {
        this.tmdbId = tmdbId;
        this.payload = payload;
        this.fetchedAt = Instant.now();
    }

    public String getId() { return id; }
    public Integer getTmdbId() { return tmdbId; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Map<String, Object> getPayload() { return payload; }
}
