package com.aiopenplatform.gateway;

import cn.hutool.json.JSONUtil;
import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ChatStreamListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public, OpenAI-compatible gateway. Authentication is enforced by ApiKeyInterceptor.
 * 流式输出（stream=true）：
 * <ul>
 *     <li>先同步完成校验/TPM 限流/预占（beginStream），失败仍返回标准 JSON 错误，不进入流；</li>
 *     <li>随后返回 SseEmitter，由独立线程池异步读上游并逐块透传，不占用容器线程等待生成；</li>
 *     <li>结算挂在流终点（usage 只在最后一 chunk）；客户端断连/超时/发送失败时中止上游并释放预占
 *         （StreamContext.settled CAS 保证结算与释放只发生一次）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class GatewayController {
    @Resource
    private PlatformService platformService;

    /** SSE 连接最长存活时间（上游生成 + 心跳余量）；对应 nginx 需同步调大 proxy_read_timeout */
    private static final long SSE_TIMEOUT_MS = 15 * 60 * 1000L;

    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "gateway-stream");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }

    @PostMapping("/chat/completions")
    public Object chat(@RequestBody ChatRequest request) {
        ApiPrincipal principal = ApiPrincipalHolder.get();
        if (Boolean.TRUE.equals(request.getStream())) {
            return chatStream(request, principal);
        }
        try {
            return ResponseEntity.ok(platformService.chat(principal, request));
        } catch (ApiRateLimitException e) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", e.getMessage());
        } catch (IllegalStateException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("余额不足") ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.BAD_GATEWAY;
            return error(status, "api_error", e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：保证 /v1 始终返回 OpenAI 兼容的 error 结构
            log.error("网关调用异常", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "api_error", "服务器内部错误");
        }
    }

    /** 流式分支：校验失败返回标准错误 JSON；通过后进入 SSE。 */
    private Object chatStream(ChatRequest request, ApiPrincipal principal) {
        final PlatformService.StreamContext ctx;
        try {
            ctx = platformService.beginStream(principal, request);
        } catch (ApiRateLimitException e) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", e.getMessage());
        } catch (IllegalStateException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("余额不足") ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.BAD_GATEWAY;
            return error(status, "api_error", e.getMessage());
        } catch (RuntimeException e) {
            log.error("网关流式预占异常", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "api_error", "服务器内部错误");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // serverCompleted：流已由本端收尾（正常 DONE / 异常中止），防止容器回调重复结算
        AtomicBoolean serverCompleted = new AtomicBoolean(false);
        // 客户端断开：立即中止上游连接止损 + 释放预占 + 失败审计（CAS 保证只结算一次）
        Runnable[] abortUpstream = new Runnable[1];
        AtomicBoolean clientGone = new AtomicBoolean(false);

        Runnable abortOnDisconnect = () -> {
            if (serverCompleted.compareAndSet(false, true)) {
                clientGone.set(true);
                if (abortUpstream[0] != null) {
                    abortUpstream[0].run();
                }
                platformService.abortStream(ctx, "客户端断开连接或流式超时");
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // emitter 已关闭
                }
            }
        };

        final String sid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        final long created = System.currentTimeMillis() / 1000;
        final String model = request.getModel();
        final int[] usage = {0, 0};
        final String[] finishReason = {null};

        ChatStreamListener listener = new ChatStreamListener() {
            @Override
            public void onConnected(Runnable abort) {
                abortUpstream[0] = abort;
            }

            @Override
            public void onDelta(String content) {
                if (clientGone.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().data(deltaChunk(sid, created, model, content)));
                } catch (Exception e) {
                    // 客户端已断开：发送失败走中止结算
                    log.debug("SSE 发送失败，按断连处理: {}", e.getMessage());
                    abortOnDisconnect.run();
                }
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens) {
                usage[0] = promptTokens;
                usage[1] = completionTokens;
            }

            @Override
            public void onFinish(String reason) {
                finishReason[0] = reason == null ? "stop" : reason;
            }
        };

        emitter.onTimeout(abortOnDisconnect);
        emitter.onError(t -> abortOnDisconnect.run());
        // 客户端断开或本端 complete 都会触发 onCompletion；由 serverCompleted 区分，避免重复结算
        emitter.onCompletion(abortOnDisconnect);

        streamExecutor.execute(() -> {
            ApiPrincipalHolder.set(principal);
            try {
                platformService.executeStream(ctx, listener);
                // 正常结束：上游已在 executeStream 内按最终 usage 结算，这里补 final chunk + [DONE]
                if (serverCompleted.compareAndSet(false, true)) {
                    try {
                        emitter.send(SseEmitter.event().data(
                                finalChunk(sid, created, model, finishReason[0], usage[0], usage[1])));
                        emitter.send(SseEmitter.event().data("[DONE]"));
                    } catch (Exception e) {
                        log.debug("SSE 收尾发送失败: {}", e.getMessage());
                    }
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                        // emitter 已关闭
                    }
                }
            } catch (RuntimeException ex) {
                // 上游失败：executeStream 内已释放预占并写失败审计（若被断连结算则幂等跳过）
                log.warn("网关流式调用失败: {}", ex.getMessage());
                if (serverCompleted.compareAndSet(false, true)) {
                    try {
                        emitter.completeWithError(ex);
                    } catch (Exception ignore) {
                        try {
                            emitter.complete();
                        } catch (Exception ignore2) {
                            // emitter 已关闭
                        }
                    }
                }
            } finally {
                ApiPrincipalHolder.clear();
            }
        });
        return emitter;
    }

    /** 普通内容块：OpenAI chat.completion.chunk 增量格式 */
    private String deltaChunk(String id, long created, String model, String content) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);
        Map<String, Object> chunk = baseChunk(id, created, model);
        chunk.put("choices", java.util.Collections.singletonList(choice));
        return JSONUtil.toJsonStr(chunk);
    }

    /** 收尾块：finish_reason + usage（usage 仅由上游在流终点给出） */
    private String finalChunk(String id, long created, String model, String finishReason, int prompt, int completion) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", new LinkedHashMap<>());
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = baseChunk(id, created, model);
        chunk.put("choices", java.util.Collections.singletonList(choice));
        Map<String, Object> usageMap = new LinkedHashMap<>();
        usageMap.put("prompt_tokens", prompt);
        usageMap.put("completion_tokens", completion);
        usageMap.put("total_tokens", prompt + completion);
        chunk.put("usage", usageMap);
        return JSONUtil.toJsonStr(chunk);
    }

    private Map<String, Object> baseChunk(String id, long created, String model) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        return chunk;
    }

    @GetMapping("/models")
    public ResponseEntity<?> models() {
        ApiPrincipal principal = ApiPrincipalHolder.get();
        java.util.List<Map<String, Object>> models = platformService.models(principal == null ? null : principal.getAppId());
        java.util.List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (Map<String, Object> model : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.get("code"));
            item.put("object", "model");
            item.put("owned_by", model.get("provider"));
            item.put("display_name", model.get("display_name"));
            data.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        detail.put("type", type);
        return ResponseEntity.status(status).body(java.util.Collections.singletonMap("error", detail));
    }
}
