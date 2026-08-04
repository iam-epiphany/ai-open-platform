package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.ITokenOrderService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * Token 发放订单控制器
 * </p>
 */
@RestController
@RequestMapping("/token-order")
public class TokenOrderController {

    @Resource
    private ITokenOrderService tokenOrderService;

    /**
     * 抢购/领取 Token 包：Lua 原子「库存校验 + 防重复领取 + 预扣 + 写 Stream」，
     * 直接返回订单 id，真正的 token 发放由后台消费者异步完成
     */
    @PostMapping("/grant/{skuId}")
    public Result grant(@PathVariable("skuId") Long skuId) {
        return tokenOrderService.grantToken(skuId);
    }

    /**
     * 我的发放订单列表
     */
    @GetMapping("/user")
    public Result myOrders() {
        return Result.ok(tokenOrderService.getUserOrders(UserHolder.getUser().getId()));
    }
}
