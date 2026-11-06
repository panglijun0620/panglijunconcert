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
import java.time.LocalDate;

@Entity
@Table(name = "analysis_metric")
public class AnalysisMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_batch_id", nullable = false)
    private DataBatch dataBatch;

    @Column(name = "metric_type", nullable = false, length = 64)
    private String metricType;

    @Column(name = "dimension_key", nullable = false, length = 160)
    private String dimensionKey;

    @Column(name = "dimension_label", nullable = false, length = 200)
    private String dimensionLabel;

    @Column(name = "metric_value", nullable = false, precision = 18, scale = 4)
    private BigDecimal metricValue;

    @Column(nullable = false, length = 32)
    private String unit;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected AnalysisMetric() {
    }

    public Long getId() {
        return id;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getMetricType() {
        return metricType;
    }

    public String getDimensionKey() {
        return dimensionKey;
    }

    public String getDimensionLabel() {
        return dimensionLabel;
    }

    public BigDecimal getMetricValue() {
        return metricValue;
    }

    public String getUnit() {
        return unit;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
