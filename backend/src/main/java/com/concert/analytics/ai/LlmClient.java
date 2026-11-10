package com.concert.analytics.ai;

import com.concert.analytics.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大模型调用客户端，采用国内模型服务通用的对话接口格式（默认接阿里云百炼的通义千问）。
 *
 * <p>这里的核心约定是“失败不算错”：超时、限流、额度用完、断网、返回体异常，
 * 全部返回 {@link Optional#empty()}，由上层改用模板出字。页面上永远不会因此报错。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
    }

    public boolean available() {
        return properties.usable();
    }

    /** 遇到限流或服务端临时故障时的重试次数（不含首次请求）。 */
    private static final int RETRY_TIMES = 2;

    /** 限流后的等待时间，按重试轮次递增，避免连续撞上限速阈值。 */
    private static final long RETRY_BACKOFF_MILLIS = 2200L;

    /**
     * 发起一次对话补全。任何异常都不外抛，统一返回空。
     *
     * <p>免费档模型通常有每分钟请求数上限，短时间内连续下拉几个对象就会返回 429；
     * 这种情况下退避重试比直接降级划算，因为多等两三秒仍能拿到模型文字。
     */
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!properties.usable()) {
            return Optional.empty();
        }
        try {
            String body = objectMapper.writeValueAsString(payload(systemPrompt, userPrompt));
            for (int attempt = 0; attempt <= RETRY_TIMES; attempt++) {
                HttpResponse<String> response = httpClient.send(
                        request(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return extractContent(response.body());
                }
                if (retryable(status) && attempt < RETRY_TIMES) {
                    log.info("模型接口返回 {}，{} 毫秒后重试第 {} 次", status,
                            RETRY_BACKOFF_MILLIS * (attempt + 1), attempt + 1);
                    Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1));
                    continue;
                }
                log.info("模型接口返回 {}，本次改用模板解读", status);
                return Optional.empty();
            }
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("模型调用被中断，本次改用模板解读");
            return Optional.empty();
        } catch (Exception exception) {
            log.info("模型调用失败（{}），本次改用模板解读", exception.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> payload(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("temperature", properties.getTemperature());
        payload.put("max_tokens", properties.getMaxTokens());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        return payload;
    }

    private HttpRequest request(String body) {
        return HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** 429 是限流，5xx 是服务端临时故障，这两类值得等一会儿再试；4xx 其余情况重试没有意义。 */
    private boolean retryable(int status) {
        return status == 429 || status / 100 == 5;
    }

    private URI endpoint() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/chat/completions");
    }

    private Optional<String> extractContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.asText("");
            return text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception exception) {
            log.info("模型返回体无法解析，本次改用模板解读");
            return Optional.empty();
        }
    }
}
