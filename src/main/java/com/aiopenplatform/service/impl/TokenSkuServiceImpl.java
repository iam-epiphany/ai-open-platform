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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_TTL;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_STOCK_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_ACTIVITY_KEY;

/**
 * <p>
 * Token 包 SKU 服务实现
 * </p>
 * 读：SKU 详情走四级缓存（ScopeCaching -&gt; Caffeine -&gt; Redis -&gt; MySQL）。
 * 写：业务代码只写 MySQL，缓存同步交给 binlog（Canal）；canal 关闭时兜底手动删缓存。
 */
@Slf4j
@Service
public class TokenSkuServiceImpl extends ServiceImpl<TokenSkuMapper, TokenSku> implements ITokenSkuService {

    @Resource
    private MultiLevelCacheService multiLevelCacheService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${canal.enabled:true}")
    private boolean canalEnabled;

    /**
     * 启动预热：仅在 Redis 库存不存在时从 DB 初始化。
     * 多节点扩容时不能覆盖已预扣的库存，否则会放大超卖风险。
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
            Boolean initialized = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(sku.getStock()));
            log.info("Credits 包库存预热: key={}, stock={}, initialized={}", key, sku.getStock(), initialized);
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
        log.info("新增 Credits 包: id={}, packageName={}, stock={}", sku.getId(), sku.getPackageName(), sku.getStock());
        return sku;
    }

    @Override
    public void updateSku(TokenSku sku) {
        TokenSku previous = getById(sku.getId());
        if (previous == null) {
            throw new IllegalArgumentException("Credits 包不存在");
        }
        if (!updateById(sku)) {
            throw new IllegalArgumentException("Credits 包不存在");
        }
        if (sku.getStock() != null) {
            String stockKey = TOKEN_STOCK_KEY + sku.getId();
            long previousStock = previous.getStock() == null ? 0L : previous.getStock();
            long delta = sku.getStock() - previousStock;
            // Redis stock may already contain in-flight reservations. Applying only
            // the admin's delta preserves those reservations; overwriting with the
            // DB value here could reopen sold stock and cause overselling.
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                if (delta != 0) {
                    stringRedisTemplate.opsForValue().increment(stockKey, delta);
                }
            } else {
                stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(sku.getStock()));
            }
        }
        multiLevelCacheService.delete(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + sku.getId());
        List<Long> activityIds = jdbcTemplate.queryForList(
                "SELECT id FROM tb_token_activity WHERE FIND_IN_SET(?,sku_ids)>0", Long.class, String.valueOf(sku.getId()));
        for (Long activityId : activityIds) {
            multiLevelCacheService.delete(JvmCaches.CACHE_ACTIVITY, TOKEN_ACTIVITY_KEY + activityId);
        }
    }
}
