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

import java.time.Instant;

@Entity
@Table(name = "spark_job_run")
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_batch_id")
    private DataBatch dataBatch;

    @Column(name = "job_type", nullable = false, length = 48)
    private String jobType;

    @Column(name = "execution_engine", nullable = false, length = 32)
    private String executionEngine;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "application_id", length = 120)
    private String applicationId;

    @Column(name = "worker_summary", length = 500)
    private String workerSummary;

    @Column(name = "input_count", nullable = false)
    private long inputCount;

    @Column(name = "success_count", nullable = false)
    private long successCount;

    @Column(name = "failed_count", nullable = false)
    private long failedCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 1000)
    private String message;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected JobRun() {
    }

    public Long getId() {
        return id;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getJobType() {
        return jobType;
    }

    public String getExecutionEngine() {
        return executionEngine;
    }

    public String getStatus() {
        return status;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getWorkerSummary() {
        return workerSummary;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public String getMessage() {
        return message;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
