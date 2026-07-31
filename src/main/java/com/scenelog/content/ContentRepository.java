package com.scenelog.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.Set;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByTmdbId(Integer tmdbId);

    boolean existsByTmdbId(Integer tmdbId);

    /**
     * 이미 적재된 tmdb_id 전체.
     * ETL이 중복 판정에 쓴다 — 건마다 exists 쿼리를 날리면 N번 왕복하므로 한 번에 읽어 메모리에서 비교한다.
     * (수백~수천 건 규모 전제. 수십만 건이 되면 배치 단위 조회로 바꿔야 한다)
     */
    @Query("select c.tmdbId from Content c")
    Set<Integer> findAllTmdbIds();
}
