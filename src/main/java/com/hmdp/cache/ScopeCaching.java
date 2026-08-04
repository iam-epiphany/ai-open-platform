package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多级缓存 L0：请求内缓存（ScopeCaching）
 * <p>
 * 基于 ThreadLocal 实现，缓存数据仅在当前请求线程内有效，
 * 由 {@link ScopeCacheInterceptor} 在请求进入时清空、请求结束时清理，避免内存泄漏。
 * 用途：同一请求内多次读取同一热点数据（如活动页聚合、SKU 详情）时直接命中，避免重复回源。
 * </p>
 */
@Slf4j
@Component
public class ScopeCaching {

    private static final ThreadLocal<Map<String, Object>> CACHE = ThreadLocal.withInitial(HashMap::new);

    /**
     * 放入请求内缓存（value 为 null 时也缓存，标记"已查询但不存在"，避免同请求内重复回源）
     */
    public void put(String key, Object value) {
        CACHE.get().put(key, value);
    }

    /**
     * 获取请求内缓存：不存在返回 null（注意与"缓存了 null 值"区分，用 {@link #containsKey} 判断）
     */
    public Object get(String key) {
        return CACHE.get().get(key);
    }

    /**
     * 判断请求内缓存是否包含该 key
     */
    public boolean containsKey(String key) {
        return CACHE.get().containsKey(key);
    }

    /**
     * 移除指定 key
     */
    public void remove(String key) {
        CACHE.get().remove(key);
    }

    /**
     * 清空请求内缓存（请求开始/结束时调用）
     */
    public void clear() {
        CACHE.remove();
    }
}
