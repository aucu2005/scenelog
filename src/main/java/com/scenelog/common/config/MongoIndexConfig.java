package com.scenelog.common.config;

import com.scenelog.reaction.ReactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * MongoDB 인덱스를 기동 시 코드로 명시 생성한다.
 *
 * <p>어노테이션 기반 자동 생성(auto-index-creation) 프로퍼티에 맡기지 않는 이유:
 * Boot 4 프리픽스 이동으로 프로퍼티가 조용히 무시된 전례가 있다(트러블슈팅 3호).
 * 인덱스는 day5 성능 실측의 대상이므로 <b>존재 여부가 코드로 보장</b>되어야 한다.
 *
 * <p>day5 측정 시나리오: mongosh로 content_offset_idx를 drop → 측정 → 재기동(자동 복구) → 측정.
 */
@Configuration
public class MongoIndexConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexConfig.class);

    @Bean
    public ApplicationRunner mongoIndexInitializer(MongoTemplate mongoTemplate) {
        return args -> {
            var indexOps = mongoTemplate.indexOps(ReactionEvent.class);

            // 집계 범위 조회 경로 (contentId, offsetSec) — day5 유무 실측 대상
            indexOps.createIndex(new Index()
                    .named("content_offset_idx")
                    .on("contentId", Sort.Direction.ASC)
                    .on("offsetSec", Sort.Direction.ASC));

            // 멱등키 — 같은 배치 재전송 시 중복 차단
            indexOps.createIndex(new Index()
                    .named("client_event_id_uniq")
                    .on("clientEventId", Sort.Direction.ASC)
                    .unique());

            log.info("reaction_events 인덱스 확인 완료: content_offset_idx, client_event_id_uniq");
        };
    }
}
