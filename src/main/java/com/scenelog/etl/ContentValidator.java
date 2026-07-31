package com.scenelog.etl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 적재 전 3종 검사: 누락 · 중복 · 정합성 (기획서 §5-A-2 / 공고 담당업무 6).
 *
 * <p>Spring 어노테이션을 붙이지 않는다 — DB·네트워크·시계에 의존하지 않는 순수 로직이라
 * 테스트에서 {@code new ContentValidator(고정시계)}로 바로 만들 수 있어야 한다.
 * 빈 등록은 {@code EtlConfig}에서 한다.
 */
public class ContentValidator {

    static final int MAX_RUNTIME_MINUTES = 600;

    private final Clock clock;

    public ContentValidator(Clock clock) {
        this.clock = clock;
    }

    public ValidationResult validate(TmdbItem item, Set<Integer> existingTmdbIds) {
        // 1) 누락 — 없으면 적재 자체가 불가능한 필드
        if (item.id() == null) return ValidationResult.rejected("MISSING_FIELD:id");
        String title = item.displayTitle();
        if (title == null || title.isBlank()) return ValidationResult.rejected("MISSING_FIELD:title");

        // 2) 정합성 — 값은 있지만 말이 안 되는 경우
        Integer runtime = item.runtime();
        if (runtime != null && (runtime <= 0 || runtime > MAX_RUNTIME_MINUTES)) {
            return ValidationResult.rejected("INTEGRITY_FAIL:runtime");
        }
        String rawDate = item.displayDate();
        boolean dateMissing = (rawDate == null || rawDate.isBlank());
        if (!dateMissing) {
            LocalDate date;
            try {
                date = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ValidationResult.rejected("INTEGRITY_FAIL:release_date");
            }
            if (date.isAfter(LocalDate.now(clock))) {
                return ValidationResult.rejected("INTEGRITY_FAIL:release_date");
            }
        }

        // 3) 중복 — 오류가 아니라 UPSERT 경로로 보낸다
        if (existingTmdbIds.contains(item.id())) return ValidationResult.duplicate();

        // 4) 누락이지만 적재는 가능한 필드 → 경고로 기록해 품질 리포트에 반영
        List<String> warnings = new ArrayList<>();
        if (runtime == null) warnings.add("MISSING_FIELD:runtime");
        if (dateMissing) warnings.add("MISSING_FIELD:release_date");
        return ValidationResult.ok(warnings);
    }
}
