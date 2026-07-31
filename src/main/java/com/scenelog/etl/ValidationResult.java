package com.scenelog.etl;

import java.util.List;

/**
 * 검증 결과.
 *
 * <p>세 갈래로 나뉘는 이유:
 * <ul>
 *   <li>{@code OK} → 적재</li>
 *   <li>{@code DUPLICATE} → 버리지 않고 UPSERT (이미 있는 건 갱신 대상이지 오류가 아니다)</li>
 *   <li>{@code REJECTED} → rejected_records 격리 후 재처리 대기</li>
 * </ul>
 * {@code warnings}는 "적재는 하지만 문제가 있었다"는 기록으로, quality_reports 집계에 쓰인다.
 */
public record ValidationResult(Status status, String rejectReason, List<String> warnings) {

    public enum Status { OK, DUPLICATE, REJECTED }

    public static ValidationResult ok(List<String> warnings) {
        return new ValidationResult(Status.OK, null, List.copyOf(warnings));
    }

    public static ValidationResult duplicate() {
        return new ValidationResult(Status.DUPLICATE, null, List.of());
    }

    public static ValidationResult rejected(String reason) {
        return new ValidationResult(Status.REJECTED, reason, List.of());
    }
}
