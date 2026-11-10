package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.OperationLogItem;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.service.OperationLogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询，仅管理员可见。
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "concert.security.admin", name = "enabled", havingValue = "true")
public class AdminLogController {

    private final OperationLogService operationLogService;

    public AdminLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping("/logs")
    public PageResponse<OperationLogItem> logs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return operationLogService.logs(page, size);
    }
}
