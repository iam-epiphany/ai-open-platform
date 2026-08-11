package com.aiopenplatform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.cache.JvmCaches;
import com.aiopenplatform.cache.MultiLevelCacheService;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.mapper.TokenSkuMapper;
import com.aiopenplatform.service.ITokenSkuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_TTL;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_STOCK_KEY;

/**
 * <p>
 * Token 包 SKU 服务实现
 * </p>
 * 读：SKU 详情走五级缓存（ScopeCaching -&gt; Caffeine -&gt; Memcache -&gt; Redis -&gt; MySQL）。
 * 写：业务代码只写 MySQL，缓存同步交给 binlog（Canal）；canal 关闭时兜底手动删缓存。
 */
@Slf4j
@Service
public class TokenSkuServiceImpl extends ServiceImpl<TokenSkuMapper, TokenSku> implements ITokenSkuService {

    @Resource
    private MultiLevelCacheService multiLevelCacheService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${canal.enabled:true}")
    private boolean canalEnabled;

    /**
     * 启动预热：将在售 SKU 的 DB 库存同步到 Redis（预扣库存，DB 为事实源）
     */
    @PostConstruct
    public void initStock() {
        List<TokenSku> list = lambdaQuery().eq(TokenSku::getStatus, 1).list();
        if (list == null || list.isEmpty()) {
            log.info("暂无在售 Token 包需要预热库存");
            return;
        }
        for (TokenSku sku : list) {
            String key = TOKEN_STOCK_KEY + sku.getId();
            stringRedisTemplate.opsForValue().set(key, String.valueOf(sku.getStock()));
            log.info("预热 Token 包库存到 Redis: key={}, stock={}", key, sku.getStock());
        }
    }

    @Override
    public TokenSku getSkuWithCache(Long id) {
        String key = TOKEN_SKU_KEY + id;
        return multiLevelCacheService.get(JvmCaches.CACHE_SKU, key, TokenSku.class,
                () -> getById(id), TOKEN_SKU_TTL);
    }

    @Override
    public TokenSku createSku(TokenSku sku) {
        save(sku);
        // 预热 Redis 预扣库存
        if (sku.getStock() != null) {
            stringRedisTemplate.opsForValue().set(TOKEN_STOCK_KEY + sku.getId(), String.valueOf(sku.getStock()));
        }
        // 缓存同步优先交给 binlog（Canal）；canal 关闭时兜底删缓存，下次读取重建
        if (!canalEnabled) {
            multiLevelCacheService.delete(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + sku.getId());
        }
        log.info("新增 Token 包: id={}, packageName={}, stock={}", sku.getId(), sku.getPackageName(), sku.getStock());
        return sku;
    }

    @Override
    public void updateSku(TokenSku sku) {
        updateById(sku);
        if (!canalEnabled) {
            multiLevelCacheService.delete(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + sku.getId());
        }
    }
}
