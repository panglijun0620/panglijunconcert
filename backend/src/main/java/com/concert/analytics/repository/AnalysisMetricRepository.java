package com.concert.analytics.repository;

import com.concert.analytics.domain.AnalysisMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisMetricRepository extends JpaRepository<AnalysisMetric, Long> {

    List<AnalysisMetric> findByDataBatchIdOrderByMetricTypeAscDimensionKeyAsc(long dataBatchId);
}
