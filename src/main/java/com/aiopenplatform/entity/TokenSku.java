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
 * Token 包 SKU：平台发放的模型 Token 额度包（拉新体验包 / 模型试用包 / 企业团队共享池）
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_token_sku")
public class TokenSku implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模型名称，如 deepseek-r1 / qwen-plus
     */
    private String modelName;

    /**
     * 模型 id（0=通用额度池；&gt;0=指定模型额度），权益按 (user_id, model_id) 维度累计
     */
    private Long modelId;

    /**
     * Token 包名称，如 10万Tokens免费体验包
     */
    private String packageName;

    /**
     * Token 额度（个）
     */
    private Long tokenAmount;

    /**
     * 包类型：1=限时体验包（一人一份）；2=企业团队共享池（同一用户限购 N 份）
     */
    private Integer type;

    /**
     * 库存
     */
    private Integer stock;

    /**
     * 每人限购数量：type=1 恒为 1；type=2 为 N
     */
    private Integer limitCount;

    /**
     * 状态：0=下架；1=上架
     */
    private Integer status;

    /**
     * 领取开始时间
     */
    private LocalDateTime beginTime;

    /**
     * 领取结束时间
     */
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
