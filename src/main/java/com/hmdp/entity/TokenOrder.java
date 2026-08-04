package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * Token 发放订单：用户抢购 Token 包成功后异步创建的发放记录（订单号=雪花 id）
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_token_order")
public class TokenOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（RedisIdWorker 雪花号，即订单号）
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 领取用户 id
     */
    private Long userId;

    /**
     * Token 包 SKU id
     */
    private Long skuId;

    /**
     * 发放的 Token 额度（个），下单时快照
     */
    private Long tokenAmount;

    /**
     * 订单状态：0=待发放；1=已发放
     */
    private Integer status;

    /**
     * 发放渠道：1=拉新活动；2=企业团队共享池
     */
    private Integer channel;

    /**
     * 下单（抢购成功）时间
     */
    private LocalDateTime createTime;

    /**
     * 实际发放时间
     */
    private LocalDateTime grantTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
