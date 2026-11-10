package com.concert.analytics.web;

import com.concert.analytics.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作日志拦截器。
 *
 * <p>只在请求结束时处理导出和管理类接口：这两类要么把数据带出系统，要么改变服务状态，
 * 都值得留痕。普通查询不记，否则日志表会被浏览记录淹没。
 */
@Component
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OperationLogInterceptor.class);

    private static final String ATTR_ACCOUNT = "oplog.account";

    private static final String ATTR_ROLE = "oplog.role";

    private final OperationLogService operationLogService;

    public OperationLogInterceptor(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            request.setAttribute(ATTR_ACCOUNT, authentication.getName());
            request.setAttribute(ATTR_ROLE, roleOf(authentication));
        } else {
            request.setAttribute(ATTR_ACCOUNT, "anonymous");
            request.setAttribute(ATTR_ROLE, "ANONYMOUS");
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            @Nullable Exception exception) {
        String uri = request.getRequestURI();
        String action = actionOf(request.getMethod(), uri);
        if (action == null) {
            return;
        }
        try {
            String account = String.valueOf(request.getAttribute(ATTR_ACCOUNT));
            String role = String.valueOf(request.getAttribute(ATTR_ROLE));
            int status = response.getStatus();
            operationLogService.record(
                    account,
                    role,
                    action,
                    targetOf(uri),
                    describeParameters(request),
                    null,
                    clientIp(request),
                    status >= 200 && status < 400);
        } catch (Exception ex) {
            // 日志写失败不能影响主请求返回
            log.warn("操作日志写入失败：{}", ex.getMessage());
        }
    }

    private static String actionOf(String method, String uri) {
        if (uri.startsWith("/api/v1/export/market")) {
            return OperationLogService.ACTION_EXPORT_MARKET;
        }
        if (uri.startsWith("/api/v1/export/risks")) {
            return OperationLogService.ACTION_EXPORT_RISKS;
        }
        if (uri.startsWith("/api/v1/export/comments")) {
            return OperationLogService.ACTION_EXPORT_COMMENTS;
        }
        if (uri.startsWith("/api/v1/admin/cache") && "DELETE".equalsIgnoreCase(method)) {
            return OperationLogService.ACTION_CLEAR_CACHE;
        }
        return null;
    }

    private static String targetOf(String uri) {
        int index = uri.indexOf("/api/v1/");
        return index < 0 ? uri : uri.substring(index);
    }

    private static String describeParameters(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            String value = String.join(",", entry.getValue());
            parts.add(entry.getKey() + "=" + value);
        }
        String joined = String.join("; ", parts);
        return joined.length() > 480 ? joined.substring(0, 480) : joined;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .collect(Collectors.joining(","));
    }
}
