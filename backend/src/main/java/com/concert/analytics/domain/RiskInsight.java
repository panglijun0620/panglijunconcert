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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sentiment_risk")
public class RiskInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_batch_id", nullable = false)
    private DataBatch dataBatch;

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    @Column(name = "scope_id", length = 160)
    private String scopeId;

    @Column(name = "object_name", nullable = false, length = 240)
    private String objectName;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @Column(name = "negative_ratio", nullable = false, precision = 6, scale = 5)
    private BigDecimal negativeRatio;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel;

    @Column(name = "primary_negative_topic", nullable = false, length = 120)
    private String primaryNegativeTopic;

    @Column(name = "sample_size", nullable = false)
    private long sampleSize;

    @DecimalMin("-1.0")
    @DecimalMax("1.0")
    @Column(name = "threshold_value", nullable = false, precision = 6, scale = 5)
    private BigDecimal thresholdValue;

    @Column(name = "minimum_sample_size", nullable = false)
    private long minimumSampleSize;

    @Column(name = "trigger_rule", nullable = false, length = 500)
    private String triggerRule;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected RiskInsight() {
    }

    public Long getId() {
        return id;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String getObjectName() {
        return objectName;
    }

    public BigDecimal getNegativeRatio() {
        return negativeRatio;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getPrimaryNegativeTopic() {
        return primaryNegativeTopic;
    }

    public long getSampleSize() {
        return sampleSize;
    }

    public BigDecimal getThresholdValue() {
        return thresholdValue;
    }

    public long getMinimumSampleSize() {
        return minimumSampleSize;
    }

    public String getTriggerRule() {
        return triggerRule;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
