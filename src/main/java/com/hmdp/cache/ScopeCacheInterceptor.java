package com.hmdp.cache;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * ScopeCaching 生命周期管理拦截器：请求进入前清空，请求结束后清理
 */
@Component
public class ScopeCacheInterceptor implements HandlerInterceptor {

    @Resource
    private ScopeCaching scopeCaching;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 清空可能残留的上一个请求数据（线程复用场景）
        scopeCaching.clear();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束清理 ThreadLocal，防止线程池复用导致的数据串扰
        scopeCaching.clear();
    }
}
