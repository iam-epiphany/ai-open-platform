package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenSku;

/**
 * <p>
 * Token 包 SKU 服务
 * </p>
 */
public interface ITokenSkuService extends IService<TokenSku> {

    /**
     * SKU 详情查询（多级缓存：ScopeCaching -&gt; Caffeine -&gt; Memcache -&gt; Redis -&gt; MySQL）
     */
    TokenSku getSkuWithCache(Long id);

    /**
     * 管理端新增 Token 包 SKU：入库 + 预热 Redis 预扣库存
     */
    TokenSku createSku(TokenSku sku);

    /**
     * 管理端更新 SKU：只写 DB，缓存同步交给 binlog（Canal）驱动
     */
    void updateSku(TokenSku sku);
}
