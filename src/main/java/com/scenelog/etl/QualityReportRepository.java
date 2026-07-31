package com.scenelog.etl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualityReportRepository extends JpaRepository<QualityReport, Long> {

    List<QualityReport> findAllByOrderByReportIdDesc();
}
