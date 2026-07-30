package com.scenelog.content;

/**
 * 콘텐츠 종류.
 * TMDB의 media_type과 매핑된다: movie→MOVIE, tv→OTT.
 * v4 초안에 있던 TRAILER는 TMDB에서 매핑할 방법이 없어 제외했다 (기획서 §5.3).
 */
public enum ContentType {
    MOVIE,
    OTT
}
