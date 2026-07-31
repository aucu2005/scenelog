package com.scenelog.etl;

import com.scenelog.content.Content;
import com.scenelog.content.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ETL 오케스트레이션: 수집 → 검증 → 정제 → 적재 (기획서 §5-A).
 *
 * <p>공고 자격요건 5("데이터 수집·정제·적재 파이프라인")와 담당업무 4·6에 정면 대응하는 클래스.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>한 건 실패가 배치 전체를 죽이지 않는다 — 격리하고 계속 간다</li>
 *   <li>실패 데이터를 버리지 않는다 — {@code rejected_records}로 격리해 재처리 가능하게 둔다</li>
 *   <li>원본은 손대기 전에 저장한다 — 정제 규칙이 바뀌면 재수집 없이 재처리한다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EtlService {

    private static final Logger log = LoggerFactory.getLogger(EtlService.class);
    private static final String SOURCE = "TMDB";

    private final TmdbClient tmdbClient;
    private final ContentValidator validator;
    private final ContentTransformer transformer;
    private final ContentRepository contentRepository;
    private final RawContentRepository rawContentRepository;
    private final RejectedRecordRepository rejectedRecordRepository;
    private final QualityReportRepository qualityReportRepository;

    /** 실행 중 누적되는 카운터. 메서드마다 인자로 끌고 다니지 않으려고 묶었다. */
    private static final class Counters {
        int fetched, inserted, updated, missingField, duplicate, integrityFail, rejected;
        final Map<String, Integer> reasonBreakdown = new LinkedHashMap<>();

        void addReason(String reason) {
            reasonBreakdown.merge(reason, 1, Integer::sum);
        }
    }

    @Transactional
    public QualityReport run(int pages) {
        long startedAt = System.currentTimeMillis();
        Counters c = new Counters();

        List<Integer> ids = tmdbClient.discoverMovieIds(pages);
        Set<Integer> existingTmdbIds = contentRepository.findAllTmdbIds();

        for (Integer tmdbId : ids) {
            try {
                processOne(tmdbId, existingTmdbIds, c);
            } catch (Exception e) {
                // 개별 건 실패는 격리하고 배치는 계속한다
                log.warn("tmdbId={} 처리 실패: {}", tmdbId, e.getMessage());
                c.rejected++;
                c.addReason("FETCH_ERROR");
                rejectedRecordRepository.save(
                        new RejectedRecord(SOURCE, tmdbId, Map.of("error", String.valueOf(e.getMessage())),
                                "FETCH_ERROR"));
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("reasonBreakdown", c.reasonBreakdown);
        details.put("pagesRequested", pages);

        QualityReport report = QualityReport.of(SOURCE, c.fetched, c.inserted, c.updated,
                c.missingField, c.duplicate, c.integrityFail, c.rejected,
                System.currentTimeMillis() - startedAt, details);

        log.info("ETL 완료 — 수집 {}건, 적재 {}건, 갱신 {}건, 격리 {}건",
                c.fetched, c.inserted, c.updated, c.rejected);
        return qualityReportRepository.save(report);
    }

    private void processOne(Integer tmdbId, Set<Integer> existingTmdbIds, Counters c) {
        Map<String, Object> payload = tmdbClient.fetchMovieDetail(tmdbId);
        c.fetched++;

        // 원본을 먼저 보관한다 — 정제가 실패해도 원본은 남아야 재처리가 가능하다
        rawContentRepository.findByTmdbId(tmdbId)
                .ifPresentOrElse(existing -> { /* 이미 있으면 다시 저장하지 않는다 */ },
                        () -> rawContentRepository.save(new RawContent(tmdbId, payload)));

        TmdbItem item = toItem(payload);
        ValidationResult result = validator.validate(item, existingTmdbIds);

        switch (result.status()) {
            case REJECTED -> {
                c.rejected++;
                c.addReason(result.rejectReason());
                if (result.rejectReason().startsWith("MISSING_FIELD")) c.missingField++;
                if (result.rejectReason().startsWith("INTEGRITY_FAIL")) c.integrityFail++;
                rejectedRecordRepository.save(
                        new RejectedRecord(SOURCE, item.id(), payload, result.rejectReason()));
            }
            case DUPLICATE -> {
                c.duplicate++;
                TmdbNormalized n = transformer.transform(item);
                contentRepository.findByTmdbId(n.tmdbId())
                        .ifPresent(existing -> {
                            existing.updateFrom(n.title(), n.contentType(), n.durationSec(), n.releaseDate());
                            c.updated++;
                        });
            }
            case OK -> {
                // 적재는 하지만 빠진 필드가 있었다는 사실을 남긴다
                result.warnings().forEach(w -> {
                    c.missingField++;
                    c.addReason(w);
                });
                TmdbNormalized n = transformer.transform(item);
                contentRepository.save(Content.builder()
                        .tmdbId(n.tmdbId())
                        .title(n.title())
                        .contentType(n.contentType())
                        .durationSec(n.durationSec())
                        .releaseDate(n.releaseDate())
                        .build());
                existingTmdbIds.add(n.tmdbId());   // 같은 배치 안의 중복도 잡아낸다
                c.inserted++;
            }
        }
    }

    /** TMDB JSON → 검증·정제가 다루는 중간 표현 */
    private TmdbItem toItem(Map<String, Object> payload) {
        return new TmdbItem(
                asInt(payload.get("id")),
                (String) payload.get("title"),
                (String) payload.get("name"),
                (String) payload.get("original_title"),
                asInt(payload.get("runtime")),
                (String) payload.get("release_date"),
                (String) payload.get("first_air_date"),
                "movie");
    }

    private Integer asInt(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /**
     * 격리된 레코드를 다시 검증·적재한다.
     * 정제 규칙을 고친 뒤 호출하면, API 재호출 없이 저장된 원본으로 재시도한다.
     */
    @Transactional
    public int reprocessRejected() {
        List<RejectedRecord> pending = rejectedRecordRepository.findByReprocessedAtIsNull();
        Set<Integer> existingTmdbIds = contentRepository.findAllTmdbIds();
        int recovered = 0;

        for (RejectedRecord record : pending) {
            if (record.getPayload() == null) continue;
            TmdbItem item = toItem(record.getPayload());
            if (validator.validate(item, existingTmdbIds).status() != ValidationResult.Status.OK) continue;

            TmdbNormalized n = transformer.transform(item);
            contentRepository.save(Content.builder()
                    .tmdbId(n.tmdbId()).title(n.title()).contentType(n.contentType())
                    .durationSec(n.durationSec()).releaseDate(n.releaseDate()).build());
            existingTmdbIds.add(n.tmdbId());
            record.markReprocessed();
            rejectedRecordRepository.save(record);
            recovered++;
        }
        log.info("재처리 완료 — 대기 {}건 중 {}건 복구", pending.size(), recovered);
        return recovered;
    }

    @Transactional(readOnly = true)
    public List<QualityReport> reports() {
        return qualityReportRepository.findAllByOrderByReportIdDesc();
    }
}
