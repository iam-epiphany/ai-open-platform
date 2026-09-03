package com.aiopenplatform.gateway;

/**
 * API Key 级限流超限异常：由 GatewayController 统一映射为 HTTP 429
 * OpenAI 风格错误体（type=rate_limit_error）。
 */
public class ApiRateLimitException extends RuntimeException {

    public ApiRateLimitException(String message) {
        super(message);
    }
}
