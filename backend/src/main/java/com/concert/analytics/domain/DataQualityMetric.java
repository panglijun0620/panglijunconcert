package com.concert.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "data_quality_metric")
public class DataQualityMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_batch_id", nullable = false)
    private DataBatch dataBatch;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "metric_key", nullable = false, length = 64)
    private String metricKey;

    @Column(name = "metric_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal metricValue;

    @Column(name = "record_count", nullable = false)
    private long recordCount;

    @Column(name = "missing_count", nullable = false)
    private long missingCount;

    @Column(name = "duplicate_count", nullable = false)
    private long duplicateCount;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected DataQualityMetric() {
    }

    public Long getId() {
        return id;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public BigDecimal getMetricValue() {
        return metricValue;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public long getMissingCount() {
        return missingCount;
    }

    public long getDuplicateCount() {
        return duplicateCount;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
