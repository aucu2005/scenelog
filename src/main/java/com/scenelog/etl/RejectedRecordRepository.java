package com.scenelog.etl;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RejectedRecordRepository extends MongoRepository<RejectedRecord, String> {

    /** 아직 재처리되지 않은 격리 레코드 — reprocess API의 대상 */
    List<RejectedRecord> findByReprocessedAtIsNull();

    long countByReprocessedAtIsNull();
}
