package com.dataforge.etl.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置(对应 application.yml 的 dataforge.llm 段)。
 * apiKey 为空时全平台 AI 功能进入「内置模拟模式」。
 */
@ConfigurationProperties(prefix = "dataforge.llm")
public class AiProperties {

    /** 是否启用 AI 助手总开关 */
    private boolean enabled = true;

    /** OpenAI 兼容协议 base-url,默认 Kimi(Moonshot) */
    private String baseUrl = "https://api.moonshot.cn/v1";

    /** API Key,建议环境变量 DATAFORGE_LLM_API_KEY 注入;空 = 模拟模式 */
    private String apiKey = "";

    private String model = "moonshot-v1-8k";

    private int timeoutSeconds = 60;

    private int maxTokens = 2048;

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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
}
