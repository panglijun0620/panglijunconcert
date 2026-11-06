ALTER TABLE sentiment_risk
    ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'ALERT' AFTER negative_ratio;

ALTER TABLE sentiment_risk
    ADD CONSTRAINT chk_sentiment_risk_level
    CHECK (risk_level IN ('ALERT', 'WATCH'));

CREATE INDEX idx_sentiment_risk_level ON sentiment_risk(data_batch_id, risk_level);
