package com.aiopenplatform.entity;

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
 * Token 账本：用户 Token 余额的每次变动流水（发放/消耗），balance_after 记录变动后余额
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_token_ledger")
public class TokenLedger implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 关联订单 id
     */
    private Long orderId;

    /**
     * 变动类型：1=发放；2=消耗
     */
    private Integer changeType;

    /**
     * 变动额度（个）
     */
    private Long changeAmount;

    /**
     * 变动后余额（个）
     */
    private Long balanceAfter;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
