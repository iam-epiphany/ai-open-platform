package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.hmdp.utils.RedisConstants.BLACKLIST_IP_KEY;

/**
 * 黑名单拦截器
 * <p>
 * 在请求进入业务层之前，检查来源 IP 是否命中 Redis 黑名单（如登录失败次数过多、
 * 触发频控阈值被拉黑的 IP），命中则直接拒绝（HTTP 403），拦截恶意流量。
 * </p>
 */
@Slf4j
public class BlackListInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public BlackListInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        // 命中黑名单则拒绝访问
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(BLACKLIST_IP_KEY + ip))) {
            log.warn("IP 已被拉黑, ip={}, uri={}", ip, request.getRequestURI());
            response.setStatus(403); // HTTP 403 Forbidden
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSONUtil.toJsonStr(Result.fail("访问受限，请稍后再试")));
            return false;
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            int idx = ip.indexOf(',');
            if (idx > 0) {
                ip = ip.substring(0, idx);
            }
        }
        return ip;
    }
}
