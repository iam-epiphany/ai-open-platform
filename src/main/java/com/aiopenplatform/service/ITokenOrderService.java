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

    /** 订单状态：待发放（预留，暂未使用） */
    int STATUS_PENDING = 0;
    /** 订单状态：已发放（发放成功落库） */
    int STATUS_GRANTED = 1;
    /** 订单状态：发放失败（终局失败留痕——抢购接口已向用户承诺成功，但 DB 终局拒绝或重试耗尽；供用户可见与对账退款） */
    int STATUS_GRANT_FAILED = 2;

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
     * 终局失败处理（幂等，仅供消费端 DB 校验不通过后调用）：
     * 写入 status={@link #STATUS_GRANT_FAILED} 的失败订单留痕，并返回当前 DB 库存
     * 供调用方把 Redis 预扣库存校正为 DB 事实。
     *
     * @return SKU 当前 DB 库存；SKU 已删除（无法留痕/校正）时返回 null
     */
    Integer handleTerminalGrantFailure(Long orderId, Long skuId, Long userId);

    /**
     * 幂等记录 status={@link #STATUS_GRANT_FAILED} 失败订单（死信丢弃前调用，
     * 防止“用户已抢购成功却拿不到 Token”被静默丢弃）。
     * SKU 已删除（token_amount 非空约束、金额未知）时仅告警日志、不落库。
     */
    void recordFailedOrder(Long orderId, Long skuId, Long userId);

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
        /** 同一消息重复投递且订单已发放成功（可 ACK，无需回滚） */
        DUPLICATE_MSG,
        /** 用户已领取/已超限（DB 未扣减：回滚 Redis 预扣 +1 精确归还后 ACK） */
        DUPLICATE_ALREADY_GRANTED,
        /** DB 库存不足（DB 已无货：失败订单留痕 + 按 DB 库存校正 Redis 后 ACK） */
        STOCK_NOT_ENOUGH,
        /** SKU 已不存在/已删除（回滚 Redis 预扣后 ACK；此前并入 DUPLICATE_MSG 会漏回滚导致库存泄漏） */
        SKU_NOT_EXISTS,
        /** 订单已终局失败留痕（死信路径写库后中断等，不再重放发放；回滚预扣收尾后 ACK） */
        ALREADY_FAILED
    }
}
