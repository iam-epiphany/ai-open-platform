package com.aiopenplatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.cache.JvmCaches;
import com.aiopenplatform.cache.MultiLevelCacheService;
import com.aiopenplatform.entity.TokenActivity;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.mapper.TokenActivityMapper;
import com.aiopenplatform.service.ITokenActivityService;
import com.aiopenplatform.service.ITokenSkuService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_ACTIVITY_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_ACTIVITY_TTL;

/**
 * <p>
 * 平台活动页服务实现
 * </p>
 * 活动页聚合数据（活动信息 + SKU 列表）是热点读数据，走四级缓存；
 * SKU 变更由 binlog（Canal）驱动删除聚合 key，下次读取重建。
 */
@Service
public class TokenActivityServiceImpl extends ServiceImpl<TokenActivityMapper, TokenActivity> implements ITokenActivityService {

    @Resource
    private ITokenSkuService tokenSkuService;
    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    @Override
    public Map<String, Object> getActivityDetail(Long id) {
        String key = TOKEN_ACTIVITY_KEY + id;
        return multiLevelCacheService.get(JvmCaches.CACHE_ACTIVITY, key, Map.class, () -> {
            TokenActivity activity = getById(id);
            if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
                // 活动不存在或已下线，缓存空值防穿透
                return null;
            }
            Map<String, Object> detail = new HashMap<>();
            detail.put("activity", activity);
            List<TokenSku> skus = new ArrayList<>();
            if (StrUtil.isNotBlank(activity.getSkuIds())) {
                for (String sid : activity.getSkuIds().split(",")) {
                    TokenSku sku = tokenSkuService.getSkuWithCache(Long.valueOf(sid.trim()));
                    if (sku != null) {
                        skus.add(sku);
                    }
                }
            }
            detail.put("skus", skus);
            return detail;
        }, TOKEN_ACTIVITY_TTL);
    }

    @Override
    public List<Map<String, Object>> listOnlineActivities() {
        // 在售活动数量少，直接查库取活动列表；每个活动复用 getActivityDetail 的聚合缓存
        List<TokenActivity> activities = lambdaQuery()
                .eq(TokenActivity::getStatus, 1)
                .orderByAsc(TokenActivity::getStartTime)
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TokenActivity activity : activities) {
            Map<String, Object> detail = getActivityDetail(activity.getId());
            if (detail != null) {
                result.add(detail);
            }
        }
        return result;
    }
}
