package com.scenelog.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 콘텐츠 메타데이터 — ETL이 TMDB에서 수집·정제해 적재한다 (기획서 §5.1 contents) */
@Entity
@Table(name = "contents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long contentId;

    /** ETL 적재 키. 중복 수집 시 이 값으로 UPSERT 판정한다 (기획서 §5-A-2) */
    @Column(nullable = false, unique = true)
    private Integer tmdbId;

    @Column(nullable = false, length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentType contentType;

    /**
     * 상영시간(초). <b>nullable</b> — TMDB의 runtime이 비어 있는 경우가 흔하다.
     * 하이라이트 검출은 이 값이 null이면 실제 최대 offset을 상한으로 쓴다 (기획서 §5.3)
     */
    private Integer durationSec;

    /** TMDB가 빈 문자열 ""을 보내는 경우가 있어 정제 단계에서 null로 변환된다 */
    private LocalDate releaseDate;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    private Content(Integer tmdbId, String title, ContentType contentType,
                    Integer durationSec, LocalDate releaseDate) {
        this.tmdbId = tmdbId;
        this.title = title;
        this.contentType = contentType;
        this.durationSec = durationSec;
        this.releaseDate = releaseDate;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    /** ETL 재수집 시 UPSERT의 UPDATE 절 — tmdb_id는 식별자이므로 바꾸지 않는다 */
    public void updateFrom(String title, ContentType contentType,
                           Integer durationSec, LocalDate releaseDate) {
        this.title = title;
        this.contentType = contentType;
        this.durationSec = durationSec;
        this.releaseDate = releaseDate;
    }
}
