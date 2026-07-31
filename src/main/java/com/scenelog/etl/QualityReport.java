package com.scenelog.etl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * ETL 실행 1회 = 1행. 공고 문구 "데이터 품질(정합성·누락·중복) 관리"의 직접 증거다 (기획서 §5.1).
 *
 * <p>이 표에 0이 아닌 숫자가 찍히는 것이 day2의 핵심 성과이며,
 * 그대로 이력서의 정량 성과 문장이 된다.
 */
@Entity
@Table(name = "quality_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long reportId;

    @Column(nullable = false)
    private OffsetDateTime batchRunAt;

    @Column(nullable = false, length = 20)
    private String source;

    private int fetchedCnt;         // API에서 받은 건수
    private int insertedCnt;        // 신규 적재
    private int updatedCnt;         // 중복 → 갱신
    private int missingFieldCnt;    // 필드 누락 (경고 + 격리 합산)
    private int duplicateCnt;       // 중복 감지
    private int integrityFailCnt;   // 정합성 위반
    private int rejectedCnt;        // 격리된 총 건수
    private long durationMs;

    /** 실패 사유별 분포. PostgreSQL JSONB — 고정 컬럼으로 만들기엔 종류가 유동적이다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    public static QualityReport of(String source, int fetchedCnt, int insertedCnt, int updatedCnt,
                                   int missingFieldCnt, int duplicateCnt, int integrityFailCnt,
                                   int rejectedCnt, long durationMs, Map<String, Object> details) {
        QualityReport r = new QualityReport();
        r.batchRunAt = OffsetDateTime.now();
        r.source = source;
        r.fetchedCnt = fetchedCnt;
        r.insertedCnt = insertedCnt;
        r.updatedCnt = updatedCnt;
        r.missingFieldCnt = missingFieldCnt;
        r.duplicateCnt = duplicateCnt;
        r.integrityFailCnt = integrityFailCnt;
        r.rejectedCnt = rejectedCnt;
        r.durationMs = durationMs;
        r.details = details;
        return r;
    }
}
