package com.concert.analytics.repository;

import com.concert.analytics.domain.DataQualityMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataQualityMetricRepository extends JpaRepository<DataQualityMetric, Long> {

    List<DataQualityMetric> findByDataBatchIdOrderBySourceTypeAscMetricKeyAsc(long dataBatchId);
}
