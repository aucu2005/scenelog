package com.scenelog.etl;

import com.scenelog.content.ContentType;

import java.time.LocalDate;

/**
 * TMDB 형식 → 내부 형식 (기획서 §5.3 정제 규칙표의 구현. ETL의 T에 해당).
 *
 * <p>전제: {@link ContentValidator}를 통과한 item만 받는다 (id·displayTitle 존재 보장).
 * 검증과 정제를 분리한 이유는 책임이 다르기 때문이다 —
 * 검증은 "받아들일지 말지"를, 정제는 "어떤 모양으로 바꿀지"를 결정한다.
 */
public class ContentTransformer {

    private static final int SECONDS_PER_MINUTE = 60;

    public TmdbNormalized transform(TmdbItem item) {
        ContentType type = "tv".equals(item.mediaType()) ? ContentType.OTT : ContentType.MOVIE;
        Integer durationSec = (item.runtime() == null) ? null : item.runtime() * SECONDS_PER_MINUTE;
        String rawDate = item.displayDate();
        LocalDate date = (rawDate == null || rawDate.isBlank()) ? null : LocalDate.parse(rawDate);
        return new TmdbNormalized(item.id(), item.displayTitle(), type, durationSec, date);
    }
}
