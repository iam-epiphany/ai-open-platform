package com.aiopenplatform.dto;

import lombok.Data;

/**
 * AI 模型调用请求体
 */
@Data
public class AiChatDTO {

    /**
     * 模型 id（必填，来自 /ai/models）
     */
    private Long modelId;

    /**
     * 提示词（必填）
     */
    private String prompt;

    /**
     * 期望输出 Token 上限（可选，默认 256，1~2048）
     */
    private Integer maxTokens;

    /**
     * 客户端幂等请求 ID（可选，同一 ID 60s 内重复请求被拦截）
     */
    private String requestId;
}
