package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.OperationLogItem;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.domain.OperationLog;
import com.concert.analytics.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 操作日志服务。
 *
 * <p>写入路径刻意不走 JPA：导出接口本身跑在只读事务里，事务结束后池里的连接仍带着只读标记，
 * 再用 JPA 或 JdbcTemplate 写会直接报 “Connection is read-only”。这里直接取一条连接、
 * 先把只读标记清掉再写，绕开整个事务上下文，稳定且不影响别处。
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    /** 动作代号，页面负责翻译成中文。 */
    public static final String ACTION_EXPORT_MARKET = "EXPORT_MARKET";
    public static final String ACTION_EXPORT_RISKS = "EXPORT_RISKS";
    public static final String ACTION_EXPORT_COMMENTS = "EXPORT_COMMENTS";
    public static final String ACTION_CLEAR_CACHE = "CLEAR_CACHE";

    private static final int MAX_PAGE_SIZE = 100;

    private static final String INSERT_SQL =
            "INSERT INTO operation_log "
                    + "(occurred_at, account, role, action, target, detail, record_count, client_ip, success) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final OperationLogRepository operationLogRepository;
    private final DataSource dataSource;

    public OperationLogService(OperationLogRepository operationLogRepository, DataSource dataSource) {
        this.operationLogRepository = operationLogRepository;
        this.dataSource = dataSource;
    }

    @Async("operationLogExecutor")
    public void record(
            String account,
            String role,
            String action,
            String target,
            String detail,
            Long recordCount,
            String clientIp,
            boolean success) {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isReadOnly()) {
                connection.setReadOnly(false);
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.setString(2, trim(account, "anonymous"));
                statement.setString(3, trim(role, "VIEWER"));
                statement.setString(4, trim(action, "UNKNOWN"));
                statement.setString(5, trim(target, ""));
                statement.setString(6, trim(detail, ""));
                if (recordCount == null) {
                    statement.setNull(7, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(7, recordCount);
                }
                statement.setString(8, trim(clientIp, ""));
                statement.setBoolean(9, success);
                statement.executeUpdate();
            }
        } catch (SQLException ex) {
            // 日志写失败不能影响主流程
            log.warn("操作日志落库失败：{}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OperationLogItem> logs(int page, int size) {
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<OperationLog> logs = operationLogRepository.findAll(pageRequest);
        return PageResponse.from(logs, OperationLogService::item);
    }

    private static OperationLogItem item(OperationLog log) {
        return new OperationLogItem(
                log.getId() == null ? 0L : log.getId(),
                log.getOccurredAt(),
                log.getAccount(),
                log.getRole(),
                log.getAction(),
                log.getTarget(),
                log.getDetail(),
                log.getRecordCount(),
                log.getClientIp(),
                log.isSuccess());
    }

    private static String trim(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() > 250 ? value.substring(0, 250) : value;
    }
}
