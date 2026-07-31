package com.scenelog.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TMDB 수집 (기획서 §5-A-1). ETL의 E에 해당한다.
 *
 * <p>2단계 호출: 목록(discover)으로 id를 모으고, id마다 상세를 받는다.
 * 목록에는 상영시간·배우가 없어서 상세 호출이 필요하다.
 */
@Component
public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);

    /** 연속 호출 간 간격. TMDB rate limit 회피 — 없으면 수백 번째에서 429가 난다 */
    private static final long CALL_INTERVAL_MS = 60;
    private static final int MAX_RETRY = 3;

    private final RestClient client;
    private final String apiKey;

    public TmdbClient(@Value("${tmdb.base-url}") String baseUrl,
                      @Value("${tmdb.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "tmdb.api-key가 비어 있습니다 — .env의 TMDB_API_KEY를 확인하세요.");
        }
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    /** 인기 영화 목록에서 tmdb id를 모은다. 페이지당 20건. */
    public List<Integer> discoverMovieIds(int pages) {
        List<Integer> ids = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            final int currentPage = page;   // 람다에서 쓰려면 effectively final 이어야 한다
            try {
                Map<String, Object> body = withRetry(() -> client.get()
                        .uri(uri -> uri.path("/discover/movie")
                                .queryParam("api_key", apiKey)
                                .queryParam("language", "ko-KR")
                                .queryParam("sort_by", "popularity.desc")
                                .queryParam("page", currentPage)
                                .build())
                        .retrieve()
                        .body(Map.class));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results =
                        (List<Map<String, Object>>) body.getOrDefault("results", List.of());
                if (results.isEmpty()) break;
                results.forEach(r -> ids.add((Integer) r.get("id")));
            } catch (Exception e) {
                // 한 페이지 실패로 전체를 중단하지 않는다 — 이미 모은 id로 계속 진행
                log.warn("discover {}페이지 수집 실패: {}", page, e.getMessage());
            }
            sleep(CALL_INTERVAL_MS);
        }
        log.info("discover 완료: {}건의 id 수집", ids.size());
        return ids;
    }

    /**
     * 영화 상세.
     *
     * <p>{@code append_to_response=credits,keywords}는 <b>지금 쓰지 않지만 반드시 포함한다</b> —
     * 나중에 관계 데이터가 필요해졌을 때 500번을 다시 호출할 수는 없다 (기획서 §13-7).
     * 파라미터 한 줄의 비용으로 확장 가능성을 사 두는 것.
     */
    public Map<String, Object> fetchMovieDetail(int tmdbId) {
        Map<String, Object> body = withRetry(() -> client.get()
                .uri(uri -> uri.path("/movie/{id}")
                        .queryParam("api_key", apiKey)
                        .queryParam("language", "ko-KR")
                        .queryParam("append_to_response", "credits,keywords")
                        .build(tmdbId))
                .retrieve()
                .body(Map.class));
        sleep(CALL_INTERVAL_MS);
        return body;
    }

    /** 429(rate limit)나 일시적 오류에 지수 백오프로 재시도한다. */
    private <T> T withRetry(java.util.function.Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                long backoff = 500L * (1L << (attempt - 1));   // 500ms → 1s → 2s
                log.warn("TMDB 호출 실패 ({}/{}), {}ms 후 재시도: {}", attempt, MAX_RETRY, backoff, e.getMessage());
                sleep(backoff);
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집 중 인터럽트", e);
        }
    }
}
