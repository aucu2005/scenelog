package com.scenelog.reaction;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ReactionEventRepository extends MongoRepository<ReactionEvent, String> {

    List<ReactionEvent> findByContentId(long contentId);

    long countByContentId(long contentId);
}
