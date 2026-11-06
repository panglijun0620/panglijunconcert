package com.concert.analytics.repository;

import com.concert.analytics.domain.RiskInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskInsightRepository extends JpaRepository<RiskInsight, Long> {

    List<RiskInsight> findByDataBatchIdOrderByNegativeRatioDesc(long dataBatchId);

    long countByDataBatchId(long dataBatchId);
}
