package com.scenelog.etl;

import com.scenelog.content.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTransformerTest {

    private final ContentTransformer transformer = new ContentTransformer();

    @Test
    void 영화_runtime은_분에서_초로_변환된다() {  // §5.3: 148분 → 8880초
        TmdbItem item = new TmdbItem(27205, "인셉션", null, "Inception", 148, "2010-07-15", null, "movie");
        TmdbNormalized n = transformer.transform(item);
        assertThat(n.tmdbId()).isEqualTo(27205);
        assertThat(n.title()).isEqualTo("인셉션");
        assertThat(n.contentType()).isEqualTo(ContentType.MOVIE);
        assertThat(n.durationSec()).isEqualTo(8880);
        assertThat(n.releaseDate()).isEqualTo(LocalDate.of(2010, 7, 15));
    }

    @Test
    void runtime_null은_null로_유지된다() {  // 검출기가 실측 최대 offset을 상한으로 쓴다 (§5.3)
        TmdbItem item = new TmdbItem(2, "A", null, "A", null, "2020-01-01", null, "movie");
        assertThat(transformer.transform(item).durationSec()).isNull();
    }

    @Test
    void tv는_OTT타입_name필드_firstAirDate를_쓴다() {
        TmdbItem tv = new TmdbItem(1399, null, "왕좌의 게임", "Game of Thrones", 57, null, "2011-04-17", "tv");
        TmdbNormalized n = transformer.transform(tv);
        assertThat(n.contentType()).isEqualTo(ContentType.OTT);
        assertThat(n.title()).isEqualTo("왕좌의 게임");
        assertThat(n.releaseDate()).isEqualTo(LocalDate.of(2011, 4, 17));
    }

    @Test
    void 한국어_제목이_비면_원제로_폴백한다() {  // §5-A-1: language=ko-KR 결손 대응
        TmdbItem item = new TmdbItem(3, "", null, "Obscure Film", 90, "2019-05-05", null, "movie");
        assertThat(transformer.transform(item).title()).isEqualTo("Obscure Film");
    }

    @Test
    void 빈문자열_날짜는_null이_된다() {  // §5.3: TMDB는 ""를 보낸다
        TmdbItem item = new TmdbItem(4, "B", null, "B", 90, "", null, "movie");
        assertThat(transformer.transform(item).releaseDate()).isNull();
    }
}
