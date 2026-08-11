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
 * 开放平台 API Key：明文仅创建时返回一次，库中只存 SHA-256 哈希；
 * key_prefix 存明文前 12 位（tok_ 开头），用于列表展示识别
 * </p>
 *
 * @author token-platform
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_token_api_key")
public class TokenApiKey implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属应用 id
     */
    private Long appId;

    /**
     * 所属用户 id
     */
    private Long userId;

    /**
     * API Key 的 SHA-256 哈希（十六进制，不存明文）
     */
    private String apiKey;

    /**
     * 明文前缀（tok_ 开头 12 位，仅用于展示识别）
     */
    private String keyPrefix;

    /**
     * 状态：1=启用；0=禁用
     */
    private Integer status;

    /**
     * 最近使用时间
     */
    private LocalDateTime lastUsedTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
