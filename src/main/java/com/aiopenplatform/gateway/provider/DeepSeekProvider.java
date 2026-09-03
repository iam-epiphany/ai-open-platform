package com.aiopenplatform.gateway.provider;

import cn.hutool.core.util.StrUtil;
import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ProviderChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/** DeepSeek's chat endpoint is OpenAI compatible, so no vendor SDK is required. */
@Component
public class DeepSeekProvider implements ModelProvider {

    @Value("${ai.deepseek.api-key:}")
    private String apiKey;

    @Value("${ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Resource
    private RestTemplate restTemplate;

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProviderChatResponse chat(ChatRequest request) {
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("尚未配置 ai.deepseek.api-key，无法调用真实模型");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", request.getMessages());
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            Map<String, Object> response = restTemplate.postForObject(
                    trimTrailingSlash(baseUrl) + "/chat/completions",
                    new HttpEntity<>(body, headers), Map.class);
            if (response == null || !(response.get("choices") instanceof java.util.List)
                    || ((java.util.List<?>) response.get("choices")).isEmpty()) {
                throw new IllegalStateException("模型服务返回了无效响应");
            }
            Map<String, Object> choice = (Map<String, Object>) ((java.util.List<?>) response.get("choices")).get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int prompt = number(usage, "prompt_tokens");
            int completion = number(usage, "completion_tokens");
            return new ProviderChatResponse(message == null ? "" : String.valueOf(message.get("content")),
                    prompt, completion, choice.get("finish_reason") == null ? "stop" : String.valueOf(choice.get("finish_reason")));
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("模型服务调用失败（HTTP " + e.getRawStatusCode() + "）：" + e.getResponseBodyAsString());
        }
    }

    private int number(Map<String, Object> map, String key) {
        if (map == null || !(map.get(key) instanceof Number)) {
            return 0;
        }
        return ((Number) map.get(key)).intValue();
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
