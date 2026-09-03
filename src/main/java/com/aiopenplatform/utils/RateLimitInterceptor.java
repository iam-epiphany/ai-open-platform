package com.aiopenplatform.utils;

import cn.hutool.json.JSONUtil;
import com.aiopenplatform.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.RATE_LIMIT_COUNT;
import static com.aiopenplatform.utils.RedisConstants.RATE_LIMIT_KEY;
import static com.aiopenplatform.utils.RedisConstants.RATE_LIMIT_V1_COUNT;

/**
 * 接口防刷频控拦截器（固定窗口限流）
 * <p>
 * 基于 Redis INCR + EXPIRE 实现固定窗口计数器：
 * 同一 IP 访问同一接口，在 60s 窗口内超过阈值（默认 30 次）直接拒绝（HTTP 429），
 * 防止脚本刷接口（如短信轰炸、恶意遍历）。分布式场景下多个实例共享同一份计数。
 * </p>
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 以 IP + 接口路径 作为限流维度；/v1 网关是 API Key 鉴权 + Credits 计费出口，放宽阈值供 Agent 高频调用
        String ip = ClientIpUtils.getClientIp(request);
        String key = RATE_LIMIT_KEY + ip + ":" + request.getRequestURI();
        int threshold = request.getRequestURI().startsWith("/v1/") ? RATE_LIMIT_V1_COUNT : RATE_LIMIT_COUNT;

        // 计数器 +1，首次访问设置窗口过期时间
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        // 超过阈值，拒绝访问
        if (count != null && count > threshold) {
            log.warn("接口访问过于频繁, ip={}, uri={}, count={}", ip, request.getRequestURI(), count);
            response.setStatus(429); // HTTP 429 Too Many Requests
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
            return false;
        }
        return true;
    }
}
