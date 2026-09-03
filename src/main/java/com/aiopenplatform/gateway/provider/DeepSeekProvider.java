package com.aiopenplatform.gateway.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ChatStreamListener;
import com.aiopenplatform.gateway.dto.ProviderChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    /**
     * 流式输出（SSE）：直连上游逐行读 data: chunk。
     * 关键点：
     * <ul>
     *     <li>请求携带 stream=true + stream_options.include_usage=true —— DeepSeek 的 usage 只在
     *         最后一个 chunk 回传（前面所有 chunk usage 为 null），结算必须挂在流终点；</li>
     *     <li>通过 {@link ChatStreamListener#onConnected} 暴露连接句柄：客户端断连时主动
     *         disconnect 上游，阻断后续 token 生成（止损）；</li>
     *     <li>readTimeout 60s 兜底：客户端异常消失时僵尸读线程最迟 60s 自毁。</li>
     * </ul>
     */
    @Override
    public void chatStream(ChatRequest request, ChatStreamListener listener) {
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("尚未配置 ai.deepseek.api-key，无法调用真实模型");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", request.getMessages());
        body.put("stream", true);
        Map<String, Object> streamOptions = new LinkedHashMap<>();
        streamOptions.put("include_usage", true);
        body.put("stream_options", streamOptions);
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(trimTrailingSlash(baseUrl) + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            final HttpURLConnection connection = conn;
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            byte[] payload = JSONUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String errBody = readErrorBody(conn);
                throw new IllegalStateException("模型服务调用失败（HTTP " + code + "）：" + errBody);
            }
            listener.onConnected(() -> {
                try {
                    connection.disconnect();
                } catch (Exception ignore) {
                    // 断连止损：忽略重复断开
                }
            });
            String finishReason = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JSONObject chunk = JSONUtil.parseObj(data);
                    JSONArray choices = chunk.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        JSONObject choice = choices.getJSONObject(0);
                        if (choice != null) {
                            JSONObject delta = choice.getJSONObject("delta");
                            if (delta != null) {
                                String content = delta.getStr("content");
                                if (StrUtil.isNotBlank(content)) {
                                    listener.onDelta(content);
                                }
                            }
                            String fr = choice.getStr("finish_reason");
                            if (StrUtil.isNotBlank(fr)) {
                                finishReason = fr;
                            }
                        }
                    }
                    JSONObject usage = chunk.getJSONObject("usage");
                    if (usage != null) {
                        Integer p = usage.getInt("prompt_tokens");
                        Integer c = usage.getInt("completion_tokens");
                        listener.onUsage(p == null ? 0 : p, c == null ? 0 : c);
                    }
                }
            }
            listener.onFinish(finishReason == null ? "stop" : finishReason);
        } catch (IOException e) {
            throw new IllegalStateException("模型流式响应中断: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignore) {
                    // 连接已关闭
                }
            }
        }
    }

    private String readErrorBody(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream()) {
            if (es == null) {
                return "";
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return StrUtil.sub(sb.toString(), 0, 500);
        } catch (IOException e) {
            return "";
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
