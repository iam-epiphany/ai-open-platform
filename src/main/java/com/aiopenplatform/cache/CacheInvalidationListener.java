package com.aiopenplatform.cache;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 跨节点 JVM 本地缓存失效监听（Redis Pub/Sub 订阅频道 cache:invalidate）
 * <p>
 * Canal 监听 binlog 后发现数据变更，除同步 L2/L3 外，还会发布失效事件；
 * 各节点（含发布者自身）收到事件后清理本地 Caffeine 缓存，保证多节点 JVM 缓存一致性。
 * </p>
 */
@Slf4j
@Component
public class CacheInvalidationListener {

    @Resource
    private JvmCaches jvmCaches;
    @Resource
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @PostConstruct
    public void registerListener() {
        redisMessageListenerContainer.addMessageListener((message, pattern) -> {
            try {
                JSONObject msg = JSONUtil.parseObj(new String(message.getBody()));
                String cacheName = msg.getStr("cacheName");
                String key = msg.getStr("key");
                if (key == null) {
                    // 整域失效（如 SKU 变更影响所有活动聚合）
                    jvmCaches.invalidateAll(cacheName);
                } else {
                    jvmCaches.invalidate(cacheName, key);
                }
            } catch (Exception e) {
                log.error("处理缓存失效广播异常", e);
            }
        }, new PatternTopic("cache:invalidate"));
    }
}
