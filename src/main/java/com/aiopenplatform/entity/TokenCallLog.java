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
 * 模型调用日志：每次 AI 调用一条，记录输入/输出 Token 消耗（与扣费同事务落库）
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_token_call_log")
public class TokenCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（雪花 ID，RedisIdWorker 生成）
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 调用用户 id
     */
    private Long userId;

    /**
     * 模型 id
     */
    private Long modelId;

    /**
     * 模型名称快照
     */
    private String modelName;

    /**
     * 输入 Token 数
     */
    private Integer promptTokens;

    /**
     * 输出 Token 数
     */
    private Integer completionTokens;

    /**
     * 总消耗 Token 数
     */
    private Integer totalTokens;

    /**
     * 调用渠道：1=网页 Playground；2=API Key
     */
    private Integer channel;

    /**
     * 客户端幂等请求 ID
     */
    private String requestId;

    /**
     * 调用时间
     */
    private LocalDateTime createTime;

}
