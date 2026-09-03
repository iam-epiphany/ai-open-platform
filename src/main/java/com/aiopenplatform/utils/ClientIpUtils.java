package com.aiopenplatform.utils;

import cn.hutool.core.util.StrUtil;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析（限流、黑名单、登录失败计数共用）。
 * <p>
 * 部署形态为 Nginx 反代：nginx 通过
 * {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for}
 * 把真实来源 IP 追加到 X-Forwarded-For 末尾，因此取最后一段即为真实客户端 IP，
 * 客户端自行伪造的前缀段会被忽略。直连后端（无代理）时回退到 RemoteAddr。
 */
public final class ClientIpUtils {

    private ClientIpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xff)) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (StrUtil.isNotBlank(candidate) && !"unknown".equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
