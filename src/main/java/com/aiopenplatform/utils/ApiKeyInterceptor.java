package com.aiopenplatform.utils;

import cn.hutool.core.util.StrUtil;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.gateway.ApiPrincipal;
import com.aiopenplatform.gateway.ApiPrincipalHolder;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.service.ITokenApiKeyService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * AI 开放接口双通道鉴权（仅拦截 /ai/**，LoginInterceptor 已放行该路径）：
 * <ul>
 *     <li>请求头带 X-Api-Key：按哈希解析用户（Redis 缓存 + DB 回填），写入 UserHolder；</li>
 *     <li>无 API Key：依赖登录态（RefreshTokenInterceptor 已填充 UserHolder）；</li>
 *     <li>两者都没有 → 401。</li>
 * </ul>
 */
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ITokenApiKeyService apiKeyService;
    private final PlatformService platformService;

    public ApiKeyInterceptor(ITokenApiKeyService apiKeyService, PlatformService platformService) {
        this.apiKeyService = apiKeyService;
        this.platformService = platformService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        if (StrUtil.isBlank(apiKey)) {
            String authorization = request.getHeader("Authorization");
            if (StrUtil.isNotBlank(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                apiKey = authorization.substring(7).trim();
            }
        }
        if (StrUtil.isNotBlank(apiKey)) {
            ApiPrincipal principal = platformService.authenticate(apiKey);
            if (principal != null) {
                UserDTO user = new UserDTO();
                user.setId(principal.getUserId());
                UserHolder.saveUser(user);
                ApiPrincipalHolder.set(principal);
                return true;
            }
            Long userId = apiKeyService.resolveUserId(apiKey);
            if (userId == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            UserDTO user = new UserDTO();
            user.setId(userId);
            UserHolder.saveUser(user);
            return true;
        }
        // 无 API Key：走登录态；未登录返回 401
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (request.getRequestURI().startsWith(request.getContextPath() + "/v1/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ApiPrincipalHolder.clear();
    }
}
