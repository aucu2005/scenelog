package com.scenelog.analytics.batch;

/** RUNNING 행이 오래 남아 있으면 "끝나지 못한 실행"이다 — 프로세스가 중간에 죽은 경우 */
public enum BatchRunStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
