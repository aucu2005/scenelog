package com.scenelog.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 검출된 하이라이트 (기획서 §5.1).
 * method 컬럼 = 알고리즘 버저닝 — 검출 방식을 바꿔도 과거 결과를 지우지 않고 비교할 수 있다.
 */
@Entity
@Table(name = "highlights")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Highlight {

    public static final String METHOD_ZSCORE_V1 = "ZSCORE_V1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long highlightId;

    @Column(nullable = false)
    private Long contentId;

    private int startSec;
    private int endSec;
    private double score;

    @Column(nullable = false, length = 20)
    private String method;

    private OffsetDateTime detectedAt;

    public Highlight(Long contentId, HighlightWindow w, String method) {
        this.contentId = contentId;
        this.startSec = w.startSec();
        this.endSec = w.endSec();
        this.score = w.score();
        this.method = method;
        this.detectedAt = OffsetDateTime.now();
    }
}
