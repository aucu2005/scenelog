package com.scenelog.etl;

import com.scenelog.etl.ValidationResult.Status;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContentValidatorTest {

    // 시계 고정: 2026-07-30 — "미래 개봉일" 판정이 오늘 날짜에 흔들리지 않게 한다
    private final Clock fixed = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);
    private final ContentValidator validator = new ContentValidator(fixed);

    private TmdbItem movie(Integer id, String title, Integer runtime, String releaseDate) {
        return new TmdbItem(id, title, null, "Original", runtime, releaseDate, null, "movie");
    }

    @Test
    void 정상_영화는_OK다() {
        ValidationResult r = validator.validate(movie(27205, "인셉션", 148, "2010-07-15"), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void id가_없으면_REJECTED_MISSING_FIELD() {
        ValidationResult r = validator.validate(movie(null, "인셉션", 148, "2010-07-15"), Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("MISSING_FIELD:id");
    }

    @Test
    void 제목이_전부_비면_REJECTED다() {
        TmdbItem noTitle = new TmdbItem(1, "", null, "", 100, "2020-01-01", null, "movie");
        ValidationResult r = validator.validate(noTitle, Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("MISSING_FIELD:title");
    }

    @Test
    void 이미_적재된_tmdb_id면_DUPLICATE다() {
        ValidationResult r = validator.validate(movie(27205, "인셉션", 148, "2010-07-15"), Set.of(27205));
        assertThat(r.status()).isEqualTo(Status.DUPLICATE);
    }

    @Test
    void runtime이_null이면_경고와_함께_OK다() {  // TMDB에서 흔한 실제 케이스 (§5.3)
        ValidationResult r = validator.validate(movie(2, "짧은 영화", null, "2020-01-01"), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).containsExactly("MISSING_FIELD:runtime");
    }

    @Test
    void runtime이_0이하이거나_600분_초과면_REJECTED_INTEGRITY() {
        assertThat(validator.validate(movie(3, "A", -5, "2020-01-01"), Set.of()).rejectReason())
                .isEqualTo("INTEGRITY_FAIL:runtime");
        assertThat(validator.validate(movie(4, "B", 601, "2020-01-01"), Set.of()).rejectReason())
                .isEqualTo("INTEGRITY_FAIL:runtime");
    }

    @Test
    void 개봉일이_미래면_REJECTED_INTEGRITY() {
        ValidationResult r = validator.validate(movie(5, "미래영화", 100, "2027-01-01"), Set.of());
        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(r.rejectReason()).isEqualTo("INTEGRITY_FAIL:release_date");
    }

    @Test
    void 날짜가_빈문자열이면_경고와_함께_OK다() {  // TMDB 실제 케이스: "" (§5.3)
        ValidationResult r = validator.validate(movie(6, "C", 100, ""), Set.of());
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.warnings()).containsExactly("MISSING_FIELD:release_date");
    }
}
