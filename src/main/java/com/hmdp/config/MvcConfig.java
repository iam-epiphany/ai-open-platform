package com.hmdp.config;

import com.hmdp.cache.ScopeCacheInterceptor;
import com.hmdp.utils.BlackListInterceptor;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RateLimitInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ScopeCacheInterceptor scopeCacheInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /imgs/** 请求映射到文件系统的图片目录，覆盖 blogs、icons 等所有子目录
        registry.addResourceHandler("/imgs/**")
                .addResourceLocations("file:D:\\Java\\dianping_project\\nginx-1.18.0\\html\\hmdp\\imgs\\");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //0.ScopeCaching 生命周期拦截器：最先执行，保证请求内缓存干净
        registry.addInterceptor(scopeCacheInterceptor).order(-100);
        //1.黑名单拦截器：最先执行，命中黑名单直接拒绝（静态图片资源不受限）
        registry.addInterceptor(new BlackListInterceptor(stringRedisTemplate))
                .excludePathPatterns("/imgs/**").order(0);
        //2.接口频控拦截器：IP+接口维度限流，防止脚本刷接口（静态图片资源不受限）
        registry.addInterceptor(new RateLimitInterceptor(stringRedisTemplate))
                .excludePathPatterns("/imgs/**").order(1);
        //3.token刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(2);
        //4.登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/token-sku/**",
                        "/token-activity/**",
                        "/imgs/**"
                ).order(3);
    }
}
