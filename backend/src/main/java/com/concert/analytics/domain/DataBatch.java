package com.concert.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "analytics_batch")
public class DataBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, unique = true, length = 80)
    private String batchId;

    @Column(name = "dataset_version", nullable = false, unique = true, length = 80)
    private String datasetVersion;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "raw_path", length = 500)
    private String rawPath;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "input_count", nullable = false)
    private long inputCount;

    @Column(name = "success_count", nullable = false)
    private long successCount;

    @Column(name = "failed_count", nullable = false)
    private long failedCount;

    @Column(name = "missing_count", nullable = false)
    private long missingCount;

    @Column(name = "duplicate_count", nullable = false)
    private long duplicateCount;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(length = 1000)
    private String notes;

    protected DataBatch() {
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getRawPath() {
        return rawPath;
    }

    public String getStatus() {
        return status;
    }

    public long getInputCount() {
        return inputCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getFailedCount() {
        return failedCount;
    }

    public long getMissingCount() {
        return missingCount;
    }

    public long getDuplicateCount() {
        return duplicateCount;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getNotes() {
        return notes;
    }
}
