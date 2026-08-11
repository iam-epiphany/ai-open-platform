package com.aiopenplatform.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存 L1：JVM 本地缓存（Caffeine）
 * <p>
 * 按缓存域拆分为多个 Cache 实例（SKU 详情 / 活动页聚合 / 用户权益），
 * 值统一为 JSON 字符串，与 Memcache（L2）、Redis（L3）保持一致。
 * 失效方式：本地直接 invalidate；跨节点通过 Redis Pub/Sub 广播 {@code cache:invalidate} 频道通知。
 * </p>
 */
@Slf4j
@Component
public class JvmCaches {

    /** 缓存域：SKU 详情 */
    public static final String CACHE_SKU = "sku";
    /** 缓存域：活动页聚合 */
    public static final String CACHE_ACTIVITY = "activity";
    /** 缓存域：用户权益 */
    public static final String CACHE_QUOTA = "quota";

    private final Map<String, Cache<String, String>> caches = new ConcurrentHashMap<>();

    public JvmCaches() {
        caches.put(CACHE_SKU, build(500, 5));
        caches.put(CACHE_ACTIVITY, build(200, 5));
        caches.put(CACHE_QUOTA, build(1000, 5));
    }

    private Cache<String, String> build(long maximumSize, long expireMinutes) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 读取本地缓存（JSON 字符串），未命中返回 null
     */
    public String get(String cacheName, String key) {
        Cache<String, String> cache = caches.get(cacheName);
        return cache == null ? null : cache.getIfPresent(key);
    }

    /**
     * 写入本地缓存
     */
    public void put(String cacheName, String key, String value) {
        Cache<String, String> cache = caches.get(cacheName);
        if (cache != null) {
            cache.put(key, value);
        }
    }

    /**
     * 失效单个 key
     */
    public void invalidate(String cacheName, String key) {
        Cache<String, String> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
            log.debug("JVM 本地缓存失效: cacheName={}, key={}", cacheName, key);
        }
    }

    /**
     * 整域失效（如 SKU 变更影响所有活动聚合）
     */
    public void invalidateAll(String cacheName) {
        Cache<String, String> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
            log.debug("JVM 本地缓存整域失效: cacheName={}", cacheName);
        }
    }
}
