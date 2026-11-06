CREATE TABLE analytics_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(80) NOT NULL,
    dataset_version VARCHAR(80) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    raw_path VARCHAR(500),
    status VARCHAR(24) NOT NULL,
    input_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    missing_count BIGINT NOT NULL DEFAULT 0,
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    collected_at TIMESTAMP NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    notes VARCHAR(1000),
    CONSTRAINT uk_analytics_batch_batch_id UNIQUE (batch_id),
    CONSTRAINT uk_analytics_batch_dataset_version UNIQUE (dataset_version)
);

CREATE TABLE clean_concert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_batch_id BIGINT NULL,
    artist_name VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL,
    concert_name VARCHAR(240) NOT NULL,
    city VARCHAR(80) NOT NULL,
    venue VARCHAR(180) NOT NULL,
    show_time TIMESTAMP NOT NULL,
    price_text VARCHAR(240) NOT NULL,
    min_price DECIMAL(10, 2) NULL,
    max_price DECIMAL(10, 2) NULL,
    sale_status VARCHAR(40) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_record_id VARCHAR(160) NULL,
    source_url VARCHAR(800) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_clean_concert_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id)
);

CREATE INDEX idx_clean_concert_show_time ON clean_concert(show_time);
CREATE INDEX idx_clean_concert_city ON clean_concert(city);
CREATE INDEX idx_clean_concert_artist ON clean_concert(artist_name);
CREATE INDEX idx_clean_concert_batch ON clean_concert(data_batch_id);

CREATE TABLE clean_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concert_id BIGINT NULL,
    data_batch_id BIGINT NULL,
    artist_name VARCHAR(120) NULL,
    comment_text TEXT NOT NULL,
    comment_time TIMESTAMP NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    user_region VARCHAR(80) NULL,
    source_type VARCHAR(40) NOT NULL,
    source_record_id VARCHAR(160) NULL,
    source_url VARCHAR(800) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    sentiment_score DECIMAL(6, 5) NULL,
    primary_topic VARCHAR(100) NULL,
    CONSTRAINT fk_clean_comment_concert FOREIGN KEY (concert_id) REFERENCES clean_concert(id),
    CONSTRAINT fk_clean_comment_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id),
    CONSTRAINT chk_clean_comment_sentiment CHECK (sentiment_score IS NULL OR (sentiment_score >= -1 AND sentiment_score <= 1))
);

CREATE INDEX idx_clean_comment_concert ON clean_comment(concert_id);
CREATE INDEX idx_clean_comment_batch ON clean_comment(data_batch_id);
CREATE INDEX idx_clean_comment_time ON clean_comment(comment_time);
CREATE INDEX idx_clean_comment_source ON clean_comment(source_type);

CREATE TABLE analysis_metric (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_batch_id BIGINT NOT NULL,
    metric_type VARCHAR(64) NOT NULL,
    dimension_key VARCHAR(160) NOT NULL,
    dimension_label VARCHAR(200) NOT NULL,
    metric_value DECIMAL(18, 4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    period_start DATE NULL,
    period_end DATE NULL,
    generated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_analysis_metric_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id),
    CONSTRAINT uk_analysis_metric UNIQUE (data_batch_id, metric_type, dimension_key)
);

CREATE INDEX idx_analysis_metric_type ON analysis_metric(metric_type);

CREATE TABLE data_quality_metric (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_batch_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    metric_key VARCHAR(64) NOT NULL,
    metric_value DECIMAL(18, 6) NOT NULL,
    record_count BIGINT NOT NULL DEFAULT 0,
    missing_count BIGINT NOT NULL DEFAULT 0,
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    generated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_data_quality_metric_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id),
    CONSTRAINT uk_data_quality_metric UNIQUE (data_batch_id, source_type, metric_key)
);

CREATE INDEX idx_data_quality_metric_batch ON data_quality_metric(data_batch_id);

CREATE TABLE sentiment_risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_batch_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(160) NULL,
    object_name VARCHAR(240) NOT NULL,
    negative_ratio DECIMAL(6, 5) NOT NULL,
    primary_negative_topic VARCHAR(120) NOT NULL,
    sample_size BIGINT NOT NULL,
    threshold_value DECIMAL(6, 5) NOT NULL,
    minimum_sample_size BIGINT NOT NULL,
    trigger_rule VARCHAR(500) NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_sentiment_risk_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id),
    CONSTRAINT chk_risk_ratio CHECK (negative_ratio >= 0 AND negative_ratio <= 1),
    CONSTRAINT chk_risk_threshold CHECK (threshold_value >= -1 AND threshold_value <= 1)
);

CREATE INDEX idx_sentiment_risk_batch ON sentiment_risk(data_batch_id);
CREATE INDEX idx_sentiment_risk_detected ON sentiment_risk(detected_at);

CREATE TABLE spark_job_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_batch_id BIGINT NULL,
    job_type VARCHAR(48) NOT NULL,
    execution_engine VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    application_id VARCHAR(120) NULL,
    worker_summary VARCHAR(500) NULL,
    input_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NULL,
    message VARCHAR(1000) NULL,
    failure_reason VARCHAR(1000) NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    CONSTRAINT fk_spark_job_run_batch FOREIGN KEY (data_batch_id) REFERENCES analytics_batch(id)
);

CREATE INDEX idx_spark_job_run_started ON spark_job_run(started_at);
CREATE INDEX idx_spark_job_run_batch ON spark_job_run(data_batch_id);
