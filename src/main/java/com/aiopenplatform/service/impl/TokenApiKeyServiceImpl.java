package com.aiopenplatform.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.entity.TokenApiKey;
import com.aiopenplatform.mapper.TokenApiKeyMapper;
import com.aiopenplatform.service.ITokenApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.API_KEY_CACHE_KEY;
import static com.aiopenplatform.utils.RedisConstants.API_KEY_CACHE_TTL;

/**
 * <p>
 * 开放平台 API Key 服务实现
 * </p>
 * 安全设计：明文 Key 仅在生成时返回一次，库中只存 SHA-256 哈希（hash 即缓存 key，可精确失效）；
 * 鉴权走 Redis 缓存（TTL 5 分钟），缓存未命中回源 DB 并回填，停用/删除时手动删缓存（Cache Aside）。
 */
@Slf4j
@Service
public class TokenApiKeyServiceImpl extends ServiceImpl<TokenApiKeyMapper, TokenApiKey> implements ITokenApiKeyService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String generateKey(Long appId, Long userId) {
        String rawKey = "tok_" + UUID.randomUUID().toString().replace("-", "");
        String hash = SecureUtil.sha256(rawKey);
        TokenApiKey key = new TokenApiKey();
        key.setAppId(appId);
        key.setUserId(userId);
        key.setApiKey(hash);
        key.setKeyPrefix(rawKey.substring(0, 12));
        key.setStatus(1);
        save(key);
        log.info("生成 API Key: id={}, appId={}, userId={}, prefix={}", key.getId(), appId, userId, key.getKeyPrefix());
        return rawKey;
    }

    @Override
    public List<TokenApiKey> listByApp(Long appId) {
        return lambdaQuery().eq(TokenApiKey::getAppId, appId)
                .orderByDesc(TokenApiKey::getCreateTime)
                .list();
    }

    @Override
    public boolean toggleStatus(Long keyId, Long userId, Integer status) {
        TokenApiKey key = getById(keyId);
        if (key == null || !key.getUserId().equals(userId)) {
            return false;
        }
        lambdaUpdate().eq(TokenApiKey::getId, keyId).set(TokenApiKey::getStatus, status).update();
        // 停用即删除鉴权缓存，下次请求回源 DB 发现已禁用
        if (status != null && status == 0) {
            deleteKeyCache(key);
        }
        log.info("{} API Key: id={}, userId={}", status == 1 ? "启用" : "禁用", keyId, userId);
        return true;
    }

    @Override
    public boolean deleteKey(Long keyId, Long userId) {
        TokenApiKey key = getById(keyId);
        if (key == null || !key.getUserId().equals(userId)) {
            return false;
        }
        deleteKeyCache(key);
        removeById(keyId);
        log.info("删除 API Key: id={}, userId={}", keyId, userId);
        return true;
    }

    @Override
    public Long resolveUserId(String rawKey) {
        String hash = SecureUtil.sha256(rawKey);
        String cacheKey = API_KEY_CACHE_KEY + hash;
        // 1. Redis 缓存命中直接返回
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return Long.valueOf(cached);
        }
        // 2. 回源 DB（hash 即库中 api_key 列）
        TokenApiKey key = lambdaQuery().eq(TokenApiKey::getApiKey, hash).one();
        if (key == null || key.getStatus() == null || key.getStatus() != 1) {
            return null;
        }
        // 3. 回填缓存并顺手更新最近使用时间（命中缓存不写库，避免放大写）
        stringRedisTemplate.opsForValue().set(cacheKey, String.valueOf(key.getUserId()), API_KEY_CACHE_TTL, TimeUnit.SECONDS);
        lambdaUpdate().eq(TokenApiKey::getId, key.getId())
                .set(TokenApiKey::getLastUsedTime, LocalDateTime.now())
                .update();
        return key.getUserId();
    }

    private void deleteKeyCache(TokenApiKey key) {
        stringRedisTemplate.delete(API_KEY_CACHE_KEY + key.getApiKey());
    }
}
