package com.scenelog.analytics.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러는 batch 프로파일에서만 켠다 (수정계획서 §5-3, 스펙 2026-09-14 §3-2).
 *
 * <p>기본 프로파일(데모·RAM 1GB 서버)은 관리자 API 수동 트리거만 남는다 — 120만 건 재집계를
 * 주기적으로 돌리는 위험을 데모 서버에 주지 않기 위해서다. 실행:
 * {@code --spring.profiles.active=batch} (+ {@code scenelog.batch.aggregate.fixed-delay=PT10M}).
 */
@Configuration
@EnableScheduling
@Profile("batch")
public class BatchSchedulingConfig {
}
