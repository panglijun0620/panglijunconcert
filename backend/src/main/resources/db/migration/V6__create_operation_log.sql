-- 操作日志：记录谁在什么时间做了什么，供管理员页面查看。
-- 只新增表，不改动任何既有表结构。
CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    account VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target VARCHAR(255) NULL,
    detail VARCHAR(500) NULL,
    record_count BIGINT NULL,
    client_ip VARCHAR(64) NULL,
    success BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_operation_log_occurred_at ON operation_log (occurred_at);
CREATE INDEX idx_operation_log_account ON operation_log (account);
