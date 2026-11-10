package com.concert.analytics.service;

import com.concert.analytics.ai.LlmClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 模型解读的缓存壳。
 *
 * <p>单独抽成一个 bean，是因为缓存注解加在同一个类的方法上时，类内部调用不走代理，注解不生效。
 *
 * <p>只缓存模型真正生成出来的文字：模型超时、被限流或未启用时这里返回 null，
 * 而缓存配置里 {@code cache-null-values} 是 false，null 不会写进 Redis。
 * 这样模板结果不会被锁在缓存里，模型恢复后下一次请求就能重新生成。
 */
@Component
public class InsightCache {

    private final LlmClient llmClient;

    public InsightCache(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 返回模型生成的解读；模型不可用时返回 null（不缓存）。
     */
    @Cacheable(cacheNames = "ai-insight", key = "@cacheKeyService.insight(#objectName)", sync = true)
    public String modelText(String objectName, String systemPrompt, String userPrompt) {
        return llmClient.complete(systemPrompt, userPrompt).orElse(null);
    }
}
