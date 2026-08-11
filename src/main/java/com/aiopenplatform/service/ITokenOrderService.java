package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.entity.TokenOrder;

import java.util.List;

/**
 * <p>
 * Token 发放订单服务
 * </p>
 */
public interface ITokenOrderService extends IService<TokenOrder> {

    /**
     * 抢购入口：Redis Lua 原子「库存校验 + 防重复领取 + 预扣库存 + 写入 Stream」，
     * 返回订单 id + 剩余库存，真正的发放由后台消费者异步完成
     */
    Result grantToken(Long skuId);

    /**
     * 发放落库（Stream 消费者调用）：
     * Redisson 锁 + 订单幂等校验 + MySQL 乐观锁扣库存 + 创建订单 + 写账本 + 更新权益
     *
     * @return 发放结果枚举
     */
    GrantResult grantTokenOrder(Long orderId, Long skuId, Long userId);

    /**
     * 查询用户发放订单列表
     */
    List<TokenOrder> getUserOrders(Long userId);

    /**
     * 发放落库结果枚举
     */
    enum GrantResult {
        /** 发放成功（可 ACK） */
        SUCCESS,
        /** 同一消息重复投递（订单已存在，可 ACK，无需回滚） */
        DUPLICATE_MSG,
        /** 用户已领取/已超限（回滚 Redis 预扣后 ACK） */
        DUPLICATE_ALREADY_GRANTED,
        /** DB 库存不足（回滚 Redis 预扣后 ACK） */
        STOCK_NOT_ENOUGH
    }
}
