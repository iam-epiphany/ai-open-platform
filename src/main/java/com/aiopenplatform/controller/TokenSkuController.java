package com.aiopenplatform.controller;


import com.aiopenplatform.dto.Result;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.service.ITokenSkuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * Token 包 SKU 控制器
 * </p>
 */
@RestController
@RequestMapping({"/credit-packages", "/token-sku"})
public class TokenSkuController {

    @Resource
    private ITokenSkuService tokenSkuService;

    /**
     * SKU 详情（四级缓存热点读：ScopeCaching -&gt; Caffeine -&gt; Redis -&gt; MySQL）
     */
    @GetMapping("/{id}")
    public Result getSku(@PathVariable("id") Long id) {
        return Result.ok(tokenSkuService.getSkuWithCache(id));
    }
}
