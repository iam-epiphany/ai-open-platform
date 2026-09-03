package com.aiopenplatform.utils;

import cn.hutool.json.JSONUtil;
import com.aiopenplatform.gateway.ApiPrincipal;
import com.aiopenplatform.gateway.ApiPrincipalHolder;
import com.aiopenplatform.gateway.KeyRateLimitService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * API Key 级 RPM 拦截器（仅 /v1/**，须注册在 ApiKeyInterceptor 之后）：
 * 鉴权完成后按 Key 维度取令牌，超限返回 OpenAI 风格 429 rate_limit_error。
 * TPM 需要请求体里的 token 估算，在服务层结算前校验（见 PlatformService）。
 */
public class KeyRateLimitInterceptor implements HandlerInterceptor {

    private final KeyRateLimitService rateLimitService;

    public KeyRateLimitInterceptor(KeyRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ApiPrincipal principal = ApiPrincipalHolder.get();
        if (principal == null || principal.getKeyId() == null) {
            return true;
        }
        if (!rateLimitService.tryAcquireRpm(principal.getKeyId())) {
            response.setStatus(429); // HTTP 429 Too Many Requests（OpenAI 兼容 rate_limit_error）
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> detail = new HashMap<>();
            detail.put("message", "API Key 请求频率超限（RPM），请稍后再试");
            detail.put("type", "rate_limit_error");
            response.getWriter().write(JSONUtil.toJsonStr(java.util.Collections.singletonMap("error", detail)));
            return false;
        }
        return true;
    }
}
