package com.scenelog.etl;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RawContentRepository extends MongoRepository<RawContent, String> {

    Optional<RawContent> findByTmdbId(Integer tmdbId);

    boolean existsByTmdbId(Integer tmdbId);
}
