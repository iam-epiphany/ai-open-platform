package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.JvmCaches;
import com.hmdp.cache.MultiLevelCacheService;
import com.hmdp.entity.TokenActivity;
import com.hmdp.entity.TokenSku;
import com.hmdp.mapper.TokenActivityMapper;
import com.hmdp.service.ITokenActivityService;
import com.hmdp.service.ITokenSkuService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.TOKEN_ACTIVITY_KEY;
import static com.hmdp.utils.RedisConstants.TOKEN_ACTIVITY_TTL;

/**
 * <p>
 * 平台活动页服务实现
 * </p>
 * 活动页聚合数据（活动信息 + SKU 列表）是热点读数据，走五级缓存；
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
}
