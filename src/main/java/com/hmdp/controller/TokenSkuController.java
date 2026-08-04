package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.TokenSku;
import com.hmdp.service.ITokenSkuService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * Token 包 SKU 控制器
 * </p>
 */
@RestController
@RequestMapping("/token-sku")
public class TokenSkuController {

    @Resource
    private ITokenSkuService tokenSkuService;

    /**
     * 管理端：新增 Token 包（拉新体验包 / 模型试用包 / 企业团队共享池）
     * 入库后预热 Redis 预扣库存，缓存同步由 binlog（Canal）驱动
     */
    @PostMapping
    public Result addSku(@RequestBody TokenSku sku) {
        tokenSkuService.createSku(sku);
        return Result.ok(sku.getId());
    }

    /**
     * 管理端：更新 SKU（业务代码只写 MySQL，缓存同步交给 binlog）
     */
    @PutMapping
    public Result updateSku(@RequestBody TokenSku sku) {
        tokenSkuService.updateSku(sku);
        return Result.ok();
    }

    /**
     * SKU 详情（五级缓存热点读：ScopeCaching -&gt; Caffeine -&gt; Memcache -&gt; Redis -&gt; MySQL）
     */
    @GetMapping("/{id}")
    public Result getSku(@PathVariable("id") Long id) {
        return Result.ok(tokenSkuService.getSkuWithCache(id));
    }
}
