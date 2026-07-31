package com.scenelog.etl;

import com.scenelog.content.ContentType;

import java.time.LocalDate;

/** 정제 완료 형식 — Content 엔티티로 1:1 매핑된다. durationSec은 null 허용 (기획서 §5.3) */
public record TmdbNormalized(
        int tmdbId,
        String title,
        ContentType contentType,
        Integer durationSec,
        LocalDate releaseDate
) {}
