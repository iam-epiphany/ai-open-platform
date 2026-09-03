package com.aiopenplatform.utils;

import cn.hutool.core.util.StrUtil;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.gateway.ApiPrincipal;
import com.aiopenplatform.gateway.ApiPrincipalHolder;
import com.aiopenplatform.gateway.PlatformService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * OpenAI-compatible 网关鉴权：
 * <ul>
 *     <li>从 X-Api-Key 或 Authorization: Bearer 读取密钥，按 SHA-256 哈希在 DB 鉴权。</li>
 *     <li>仅接受应用中生成的 tok_ 密钥，普通登录 token 不能替代。</li>
 * </ul>
 */
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final PlatformService platformService;

    public ApiKeyInterceptor(PlatformService platformService) {
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
            writeUnauthorized(response);
            return false;
        }
        // 无 API Key：走登录态；未登录返回 401
        if (UserHolder.getUser() == null) {
            writeUnauthorized(response);
            return false;
        }
        if (request.getRequestURI().startsWith(request.getContextPath() + "/v1/")) {
            writeUnauthorized(response);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ApiPrincipalHolder.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"type\":\"authentication_error\",\"message\":\"缺少或无效的 API Key\"}}");
    }
}
