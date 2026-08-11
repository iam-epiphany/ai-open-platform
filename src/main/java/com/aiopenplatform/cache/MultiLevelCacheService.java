package com.aiopenplatform.cache;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import net.rubyeye.xmemcached.MemcachedClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.aiopenplatform.utils.RedisConstants.CACHE_NULL_TTL;
import static com.aiopenplatform.utils.RedisConstants.CACHE_INVALIDATE_CHANNEL;

/**
 * 多级缓存读写服务：ScopeCaching(L0) -&gt; JVM Caffeine(L1) -&gt; Memcache(L2) -&gt; Redis(L3) -&gt; MySQL(L4)
 * <p>
 * 读链路：请求进来逐级查询，未命中则回源下一级，最终回源 MySQL（loader）；
 * 回源成功后逐级写回。Redis 层用 SETNX 互斥锁防缓存击穿，空值短 TTL 防穿透。
 * 写链路：业务代码只写 MySQL，缓存同步交给 Canal（binlog 驱动），
 * 本类提供 writeThrough / delete 供 Canal 监听器使用；canal 关闭时由业务代码调用兜底。
 * </p>
 */
@Slf4j
@Component
public class MultiLevelCacheService {

    /** Redis 空值缓存标记（防穿透） */
    private static final String CACHE_NULL_MARK = "";

    @Resource
    private ScopeCaching scopeCaching;
    @Resource
    private JvmCaches jvmCaches;
    @Resource
    private MemcachedClient memcachedClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 五级缓存读取：ScopeCaching -&gt; Caffeine -&gt; Memcache -&gt; Redis -&gt; MySQL(loader)
     *
     * @param cacheName    缓存域（JvmCaches.CACHE_*）
     * @param key          缓存 key
     * @param type         返回类型
     * @param loader       MySQL 回源函数
     * @param redisTtlSec  Redis 缓存 TTL（秒）
     * @param <T>          返回类型
     * @return 查询结果，不存在返回 null
     */
    public <T> T get(String cacheName, String key, Class<T> type, Supplier<T> loader, Long redisTtlSec) {
        // ============ L0 请求内缓存 ============
        if (scopeCaching.containsKey(key)) {
            log.debug("【缓存命中 L0 ScopeCaching】key={}", key);
            return (T) scopeCaching.get(key);
        }

        // ============ L1 JVM 本地缓存（Caffeine） ============
        String jvmJson = jvmCaches.get(cacheName, key);
        if (StrUtil.isNotBlank(jvmJson)) {
            T t = JSONUtil.toBean(jvmJson, type);
            scopeCaching.put(key, t);
            log.debug("【缓存命中 L1 Caffeine】key={}", key);
            return t;
        }

        // ============ L2 Memcache ============
        try {
            Object memValue = memcachedClient.get(key);
            if (memValue != null) {
                T t = JSONUtil.toBean((String) memValue, type);
                jvmCaches.put(cacheName, key, (String) memValue);
                scopeCaching.put(key, t);
                log.debug("【缓存命中 L2 Memcache】key={}", key);
                return t;
            }
        } catch (Exception e) {
            // Memcache 故障降级：继续下一级，不阻断主链路
            log.warn("Memcache 读取失败，降级到 Redis: key={}, err={}", key, e.getMessage());
        }

        // ============ L3 Redis ============
        String redisJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(redisJson)) {
            T t = JSONUtil.toBean(redisJson, type);
            writeBackToUpperLevels(cacheName, key, t, redisJson);
            log.debug("【缓存命中 L3 Redis】key={}", key);
            return t;
        }
        if (redisJson != null) {
            // 命中空值缓存（防穿透），直接返回不存在
            scopeCaching.put(key, null);
            return null;
        }

        // ============ L4 MySQL 回源（互斥锁防击穿） ============
        return queryFromDbWithMutex(cacheName, key, type, loader, redisTtlSec);
    }

    /**
     * Redis 层互斥锁回源：防止热点 key 过期瞬间大量请求同时打穿到 MySQL
     */
    private <T> T queryFromDbWithMutex(String cacheName, String key, Class<T> type,
                                       Supplier<T> loader, Long redisTtlSec) {
        String lockKey = "lock:cache:" + key;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 未拿到锁：说明其他线程正在回源，休眠后重试（走递归重新走缓存链路）
                Thread.sleep(50);
                return get(cacheName, key, type, loader, redisTtlSec);
            }
            // 拿到锁后双重检查：可能其他线程已回源写入
            String redisJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(redisJson)) {
                T t = JSONUtil.toBean(redisJson, type);
                writeBackToUpperLevels(cacheName, key, t, redisJson);
                return t;
            }
            if (redisJson != null) {
                scopeCaching.put(key, null);
                return null;
            }

            // 回源 MySQL
            T t = loader.get();
            if (t == null) {
                // 空值短 TTL 缓存，防穿透
                stringRedisTemplate.opsForValue().set(key, CACHE_NULL_MARK, CACHE_NULL_TTL, TimeUnit.MINUTES);
                scopeCaching.put(key, null);
                log.debug("【缓存回源 MySQL 未命中，写入空值缓存】key={}", key);
                return null;
            }
            String json = JSONUtil.toJsonStr(t);
            stringRedisTemplate.opsForValue().set(key, json, redisTtlSec, TimeUnit.SECONDS);
            writeBackToUpperLevels(cacheName, key, t, json);
            log.debug("【缓存回源 MySQL 并逐级写回】key={}", key);
            return t;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("缓存回源被中断", e);
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * 回源/命中低层缓存后，向 L0/L1/L2 逐级写回
     */
    private <T> void writeBackToUpperLevels(String cacheName, String key, T t, String json) {
        scopeCaching.put(key, t);
        jvmCaches.put(cacheName, key, json);
        try {
            memcachedClient.set(key, 600, json); // Memcache 写回 TTL 10 分钟
        } catch (Exception e) {
            log.warn("Memcache 写回失败: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 五级删除：本节点 L0/L1 直接删，L2/L3 共享层删除，并广播 JVM 失效事件给其他节点
     *
     * @param cacheName 缓存域
     * @param key       缓存 key
     */
    public void delete(String cacheName, String key) {
        scopeCaching.remove(key);
        jvmCaches.invalidate(cacheName, key);
        try {
            memcachedClient.delete(key);
        } catch (Exception e) {
            log.warn("Memcache 删除失败: key={}, err={}", key, e.getMessage());
        }
        stringRedisTemplate.delete(key);
        publishInvalidate(cacheName, key);
    }

    /**
     * 写透传（Canal binlog 驱动）：详情类数据变更后，将新数据直接写入 L2/L3 共享层；
     * L1 由广播失效事件处理，避免本地脏写。
     */
    public void writeThrough(String cacheName, String key, Object value, Long redisTtlSec) {
        String json = JSONUtil.toJsonStr(value);
        try {
            memcachedClient.set(key, 600, json);
        } catch (Exception e) {
            log.warn("Memcache 写透传失败: key={}, err={}", key, e.getMessage());
        }
        stringRedisTemplate.opsForValue().set(key, json, redisTtlSec, TimeUnit.SECONDS);
        publishInvalidate(cacheName, key);
        log.info("【binlog 写透传】cacheName={}, key={}", cacheName, key);
    }

    /**
     * 广播 JVM 本地缓存失效事件（Redis Pub/Sub）：key 为 null 时表示整域失效
     */
    public void publishInvalidate(String cacheName, String key) {
        JSONObject msg = new JSONObject();
        msg.set("cacheName", cacheName);
        msg.set("key", key);
        stringRedisTemplate.convertAndSend(CACHE_INVALIDATE_CHANNEL, msg.toString());
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
