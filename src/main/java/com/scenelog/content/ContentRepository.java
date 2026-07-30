package com.scenelog.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByTmdbId(Integer tmdbId);

    boolean existsByTmdbId(Integer tmdbId);
}
