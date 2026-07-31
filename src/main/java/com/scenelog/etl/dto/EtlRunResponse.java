package com.scenelog.etl.dto;

import com.scenelog.etl.QualityReport;

import java.time.OffsetDateTime;
import java.util.Map;

/** ETL 실행 결과 = 품질 리포트 1행을 그대로 보여준다 */
public record EtlRunResponse(
        Long reportId,
        OffsetDateTime batchRunAt,
        String source,
        int fetchedCnt,
        int insertedCnt,
        int updatedCnt,
        int missingFieldCnt,
        int duplicateCnt,
        int integrityFailCnt,
        int rejectedCnt,
        long durationMs,
        Map<String, Object> details
) {
    public static EtlRunResponse from(QualityReport r) {
        return new EtlRunResponse(r.getReportId(), r.getBatchRunAt(), r.getSource(),
                r.getFetchedCnt(), r.getInsertedCnt(), r.getUpdatedCnt(),
                r.getMissingFieldCnt(), r.getDuplicateCnt(), r.getIntegrityFailCnt(),
                r.getRejectedCnt(), r.getDurationMs(), r.getDetails());
    }
}
