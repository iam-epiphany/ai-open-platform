package com.aiopenplatform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.entity.TokenApp;
import com.aiopenplatform.entity.TokenApiKey;
import com.aiopenplatform.mapper.TokenAppMapper;
import com.aiopenplatform.service.ITokenAppService;
import com.aiopenplatform.service.ITokenApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 开放平台应用服务实现
 * </p>
 */
@Slf4j
@Service
public class TokenAppServiceImpl extends ServiceImpl<TokenAppMapper, TokenApp> implements ITokenAppService {

    @Resource
    private ITokenApiKeyService apiKeyService;

    @Override
    @Transactional
    public Map<String, Object> createApp(Long userId, String appName, String description) {
        TokenApp app = new TokenApp();
        app.setUserId(userId);
        app.setAppName(appName);
        app.setDescription(description == null ? "" : description);
        app.setStatus(1);
        save(app);
        // 创建应用自动生成第一个 API Key（明文仅此一次返回）
        String apiKeyPlain = apiKeyService.generateKey(app.getId(), userId);
        log.info("创建开放平台应用: id={}, userId={}, appName={}", app.getId(), userId, appName);
        Map<String, Object> data = new HashMap<>();
        data.put("app", app);
        data.put("apiKeyPlain", apiKeyPlain);
        return data;
    }

    @Override
    public List<TokenApp> listByUser(Long userId) {
        return lambdaQuery().eq(TokenApp::getUserId, userId)
                .orderByDesc(TokenApp::getCreateTime)
                .list();
    }

    @Override
    @Transactional
    public boolean deleteApp(Long appId, Long userId) {
        TokenApp app = getById(appId);
        if (app == null || !app.getUserId().equals(userId)) {
            return false;
        }
        // 连带删除该应用的全部 API Key（含鉴权缓存）
        List<TokenApiKey> keys = apiKeyService.listByApp(appId);
        for (TokenApiKey key : keys) {
            apiKeyService.deleteKey(key.getId(), userId);
        }
        removeById(appId);
        log.info("删除开放平台应用: id={}, userId={}", appId, userId);
        return true;
    }
}
