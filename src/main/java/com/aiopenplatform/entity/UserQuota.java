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
 * 用户 Token 权益：按（用户, 模型）维度的额度余额（model_id=0 表示通用池）
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_quota")
public class UserQuota implements Serializable {

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
     * 模型 id：0=通用池；其他=指定模型额度（唯一索引 (user_id, model_id)）
     */
    private Long modelId;

    /**
     * 累计发放额度（个）
     */
    private Long totalTokens;

    /**
     * 已消耗额度（个）
     */
    private Long usedTokens;

    /**
     * 可用余额（个）= totalTokens - usedTokens
     */
    private Long balance;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
