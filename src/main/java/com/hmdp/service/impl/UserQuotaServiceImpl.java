package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.JvmCaches;
import com.hmdp.cache.MultiLevelCacheService;
import com.hmdp.entity.UserQuota;
import com.hmdp.mapper.UserQuotaMapper;
import com.hmdp.service.IUserQuotaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.TOKEN_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.TOKEN_QUOTA_TTL;

/**
 * <p>
 * 用户 Token 权益服务实现
 * </p>
 * 权益查询走五级缓存；发放时原子 upsert（唯一索引 (user_id, model_id)），
 * 缓存失效由 binlog（Canal）驱动，canal 关闭时兜底手动删缓存。
 */
@Slf4j
@Service
public class UserQuotaServiceImpl extends ServiceImpl<UserQuotaMapper, UserQuota> implements IUserQuotaService {

    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    @Value("${canal.enabled:true}")
    private boolean canalEnabled;

    @Override
    public UserQuota getQuotaWithCache(Long userId, Long modelId) {
        String key = TOKEN_QUOTA_KEY + userId + ":" + modelId;
        return multiLevelCacheService.get(JvmCaches.CACHE_QUOTA, key, UserQuota.class,
                () -> getQuotaFromDb(userId, modelId), TOKEN_QUOTA_TTL);
    }

    @Override
    public void grantQuota(Long userId, Long modelId, Long amount) {
        getBaseMapper().upsertGrant(userId, modelId, amount);
        if (!canalEnabled) {
            multiLevelCacheService.delete(JvmCaches.CACHE_QUOTA, TOKEN_QUOTA_KEY + userId + ":" + modelId);
        }
    }

    @Override
    public UserQuota getQuotaFromDb(Long userId, Long modelId) {
        return lambdaQuery().eq(UserQuota::getUserId, userId)
                .eq(UserQuota::getModelId, modelId)
                .one();
    }
}
