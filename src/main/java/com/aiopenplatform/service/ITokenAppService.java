package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenApp;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 开放平台应用服务
 * </p>
 */
public interface ITokenAppService extends IService<TokenApp> {

    /**
     * 创建应用（自动生成第一个 API Key）
     *
     * @return {app: 应用实体, apiKeyPlain: 首个 Key 明文（仅此一次）}
     */
    Map<String, Object> createApp(Long userId, String appName, String description);

    /**
     * 我的应用列表（创建时间倒序）
     */
    List<TokenApp> listByUser(Long userId);

    /**
     * 删除应用（连带删除其全部 API Key 与鉴权缓存）
     */
    boolean deleteApp(Long appId, Long userId);
}
