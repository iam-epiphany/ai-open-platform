package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author
 * @since 2026
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 差异化下单入口：按优惠券类型分派
     * type=0 普通券：同步下单；type=1 秒杀券 / type=2 限购券：Lua 预扣 + Kafka 异步落库
     */
    @PostMapping("order/{id}")
    public Result createOrder(@PathVariable("id") Long voucherId) {
        return voucherOrderService.createOrder(voucherId);
    }
}
