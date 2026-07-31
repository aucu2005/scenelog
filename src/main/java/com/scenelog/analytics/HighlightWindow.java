package com.scenelog.analytics;

/** 검출된 하이라이트 구간 — highlights 테이블 한 행에 대응. score는 구간 내 최대 z-score */
public record HighlightWindow(int startSec, int endSec, double score) {}
