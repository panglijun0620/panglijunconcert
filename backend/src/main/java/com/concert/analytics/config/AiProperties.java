package com.concert.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大模型解读的接入配置。
 *
 * <p>默认关闭。没有配置密钥时整条链路只走模板，页面照常出字，不会报错也不会卡住。
 * 密钥从环境变量 {@code AI_API_KEY} 读取，不写进代码和仓库。
 */
@ConfigurationProperties(prefix = "concert.ai")
public class AiProperties {

    private boolean enabled;
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen-plus";
    private String apiKey = "";
    private int connectTimeoutMillis = 2000;
    private int readTimeoutMillis = 6000;
    private int maxTokens = 320;
    private double temperature = 0.3;

    /**
     * 只有开关打开且密钥非空时才真正发起模型调用，否则一律走模板。
     */
    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
