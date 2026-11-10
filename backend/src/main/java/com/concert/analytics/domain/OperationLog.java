package com.concert.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 操作日志。
 *
 * <p>由拦截器在请求结束后自动写入，记录账号、角色、动作、对象、查询条件、来源地址。
 * 页面上的导出、清缓存这类会改变数据或带走数据的操作都会留痕。
 */
@Entity
@Table(name = "operation_log")
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 64)
    private String account;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 255)
    private String target;

    @Column(length = 500)
    private String detail;

    @Column(name = "record_count")
    private Long recordCount;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(nullable = false)
    private boolean success = true;

    protected OperationLog() {
    }

    public OperationLog(
            Instant occurredAt,
            String account,
            String role,
            String action,
            String target,
            String detail,
            Long recordCount,
            String clientIp,
            boolean success) {
        this.occurredAt = occurredAt;
        this.account = account;
        this.role = role;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.recordCount = recordCount;
        this.clientIp = clientIp;
        this.success = success;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getAccount() {
        return account;
    }

    public String getRole() {
        return role;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetail() {
        return detail;
    }

    public Long getRecordCount() {
        return recordCount;
    }

    public String getClientIp() {
        return clientIp;
    }

    public boolean isSuccess() {
        return success;
    }
}
