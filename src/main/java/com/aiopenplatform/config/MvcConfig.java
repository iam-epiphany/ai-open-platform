package com.aiopenplatform.config;

import com.aiopenplatform.cache.ScopeCacheInterceptor;
import com.aiopenplatform.service.ITokenApiKeyService;
import com.aiopenplatform.utils.ApiKeyInterceptor;
import com.aiopenplatform.utils.BlackListInterceptor;
import com.aiopenplatform.utils.LoginInterceptor;
import com.aiopenplatform.utils.RateLimitInterceptor;
import com.aiopenplatform.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ScopeCacheInterceptor scopeCacheInterceptor;

    @Autowired
    private ITokenApiKeyService apiKeyService;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /imgs/** 请求映射到文件系统的图片目录，覆盖 blogs、icons 等所有子目录
        registry.addResourceHandler("/imgs/**")
                .addResourceLocations("file:" + new File(uploadDir).getAbsolutePath() + File.separator);
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
                        "/ai/**",
                        "/imgs/**"
                ).order(3);
        //5.AI 开放接口鉴权拦截器：/ai/** 双通道（登录态或 X-Api-Key）；模型目录公开放行
        registry.addInterceptor(new ApiKeyInterceptor(apiKeyService))
                .addPathPatterns("/ai/**")
                .excludePathPatterns("/ai/models")
                .order(4);
    }
}
