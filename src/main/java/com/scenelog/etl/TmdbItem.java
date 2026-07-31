package com.scenelog.etl;

/**
 * TMDB 응답에서 검증·정제에 필요한 필드만 추린 중간 표현.
 * movie는 title/releaseDate, tv는 name/firstAirDate를 쓴다 — 선택 로직은 아래 헬퍼가 담당한다.
 */
public record TmdbItem(
        Integer id,
        String title,          // movie
        String name,           // tv
        String originalTitle,
        Integer runtime,       // 분 단위. null 흔함 (기획서 §5.3)
        String releaseDate,    // movie. "" 가능
        String firstAirDate,   // tv
        String mediaType       // "movie" | "tv"
) {
    /** 표시용 제목: 매체별 필드 → 원제 순서로 폴백 (language=ko-KR 결손 대응, §5-A-1) */
    public String displayTitle() {
        String primary = "tv".equals(mediaType) ? name : title;
        if (primary != null && !primary.isBlank()) return primary;
        return originalTitle;
    }

    /** 매체별 날짜 필드 선택 */
    public String displayDate() {
        return "tv".equals(mediaType) ? firstAirDate : releaseDate;
    }
}
