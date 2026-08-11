package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenApiKey;

import java.util.List;

/**
 * <p>
 * 开放平台 API Key 服务
 * </p>
 */
public interface ITokenApiKeyService extends IService<TokenApiKey> {

    /**
     * 生成 API Key：明文仅此一次返回（落库只存 SHA-256 哈希 + 前缀）
     *
     * @return 明文 Key（tok_ 开头，前端仅展示一次）
     */
    String generateKey(Long appId, Long userId);

    /**
     * 应用下的 Key 列表（不含明文，创建时间倒序）
     */
    List<TokenApiKey> listByApp(Long appId);

    /**
     * 启用/禁用 Key（停用时删除鉴权缓存）
     */
    boolean toggleStatus(Long keyId, Long userId, Integer status);

    /**
     * 删除 Key（连带删除鉴权缓存）
     */
    boolean deleteKey(Long keyId, Long userId);

    /**
     * 鉴权：根据明文 Key 解析用户 id（哈希 → Redis 缓存 → DB 回填）
     *
     * @return 用户 id；Key 不存在/已禁用返回 null
     */
    Long resolveUserId(String rawKey);
}
