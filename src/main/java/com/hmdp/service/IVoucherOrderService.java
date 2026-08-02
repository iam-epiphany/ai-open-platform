package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 差异化下单入口：按优惠券类型分派（0 普通券同步下单；1/2 秒杀/限购券走 Lua+Kafka）
     */
    Result createOrder(Long voucherId);

    Result seckillVoucher(Long voucherId);

    /**
     * 秒杀订单落库（Kafka 消费者调用），返回是否落库成功
     */
    boolean createVoucherOrder(VoucherOrder voucherOrder);
}
