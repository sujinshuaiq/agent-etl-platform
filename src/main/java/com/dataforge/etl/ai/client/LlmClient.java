package com.dataforge.etl.ai.client;

import com.dataforge.etl.ai.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关:OpenAI 兼容 chat completions 客户端。
 *
 * <p>双模运行:
 * <ul>
 *   <li>api-key 为空 / enabled=false → 模拟模式,chat() 直接返回 null,由上层走规则/模板;</li>
 *   <li>配置了 api-key → 真实调用 {base-url}/chat/completions,Bearer 鉴权,
 *       支持 messages / tools / tool_choice 参数,超时与异常兜底返回 null。</li>
 * </ul></p>
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public LlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 是否处于内置模拟模式(未启用或未配置 api-key) */
    public boolean isMock() {
        return !properties.isEnabled()
                || properties.getApiKey() == null
                || properties.getApiKey().isBlank();
    }

    /**
     * 纯对话调用。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 模型回复文本;模拟模式或调用失败返回 null(上层兜底)
     */
    public String chat(String systemPrompt, String userMessage) {
        return chat(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)), null);
    }

    /**
     * 完整 chat completions 调用(支持 tools / tool_choice)。
     *
     * @param messages OpenAI 消息列表
     * @param tools    工具定义(JSON Schema 数组),null 表示不启用 function calling
     * @return 响应 JSON(含 choices);失败或模拟模式返回 null
     */
    public JsonNode chatForCompletion(List<Map<String, Object>> messages, ArrayNode tools) {
        if (isMock()) {
            return null;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getModel());
            body.put("max_tokens", properties.getMaxTokens());
            body.put("temperature", 0.3);
            body.set("messages", objectMapper.valueToTree(messages));
            if (tools != null && !tools.isEmpty()) {
                body.set("tools", tools);
                body.put("tool_choice", "auto");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getApiKey());
            String url = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            String resp = restTemplate.postForObject(url, new HttpEntity<>(body.toString(), headers), String.class);
            return resp == null ? null : objectMapper.readTree(resp);
        } catch (Exception e) {
            log.warn("LLM 调用失败,回退到模拟模式: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 便捷方法:取第一条 choice 的文本内容。
     */
    public String chat(List<Map<String, String>> messages, ArrayNode tools) {
        JsonNode resp = chatForCompletion(
                messages.stream().map(m -> (Map<String, Object>) (Map<?, ?>) m).toList(), tools);
        if (resp == null) {
            return null;
        }
        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : null;
    }
}
