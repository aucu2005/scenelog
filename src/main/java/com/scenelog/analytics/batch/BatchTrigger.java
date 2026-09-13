package com.scenelog.analytics.batch;

/** 누가 집계를 시작했나 — 관리자 API(MANUAL) / batch 프로파일 스케줄러(SCHEDULED) */
public enum BatchTrigger {
    MANUAL,
    SCHEDULED
}
