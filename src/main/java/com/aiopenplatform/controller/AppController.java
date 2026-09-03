package com.aiopenplatform.controller;

import cn.hutool.core.util.StrUtil;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 开放平台应用 / API Key 管理
 * <p>
 * 安全约定：API Key 明文仅在创建时返回一次，库中只存 SHA-256 哈希；
 * 列表接口只返回前缀（tok_ 开头 12 位）用于识别。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/apps")
public class AppController {

    @Resource
    private PlatformService platformService;

    /**
     * 创建应用（自动生成第一个 API Key，明文仅此一次返回）
     */
    @PostMapping
    public Result createApp(@RequestBody Map<String, String> body) {
        UserDTO user = UserHolder.getUser();
        if (body == null) {
            return Result.fail("请输入应用名称");
        }
        String appName = body.get("appName");
        if (StrUtil.isBlank(appName)) {
            return Result.fail("请输入应用名称");
        }
        if (appName.length() > 32) {
            return Result.fail("应用名称过长（最多 32 字）");
        }
        Map<String, Object> data;
        try {
            data = platformService.createApp(user.getId(), appName.trim(), body.get("description"));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        data.put("tip", "密钥仅显示一次，请立即复制保存");
        return Result.ok(data);
    }

    /**
     * 我的应用列表（含每个应用的 Key 前缀列表，不含明文）
     */
    @GetMapping
    public Result listApps() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(platformService.listApps(user.getId()));
    }

    /**
     * 为应用新建 API Key（明文仅此一次返回）
     */
    @PostMapping("/{id}/keys")
    public Result createKey(@PathVariable("id") Long appId) {
        UserDTO user = UserHolder.getUser();
        String plain;
        try {
            plain = platformService.createKeyForUser(appId, user.getId());
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("apiKeyPlain", plain);
        data.put("apiKey", plain);
        data.put("tip", "密钥仅显示一次，请立即复制保存");
        return Result.ok(data);
    }

    /**
     * 启用/禁用 Key（停用立即删除鉴权缓存）
     */
    @PutMapping("/keys/{keyId}")
    public Result toggleKey(@PathVariable("keyId") Long keyId, @RequestBody Map<String, Integer> body) {
        UserDTO user = UserHolder.getUser();
        if (body == null) {
            return Result.fail("状态参数错误");
        }
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            return Result.fail("状态参数错误");
        }
        boolean ok = platformService.setKeyStatus(keyId, user.getId(), status);
        return ok ? Result.ok() : Result.fail("密钥不存在");
    }

    /**
     * 删除应用（连带删除全部 Key 与鉴权缓存）
     */
    @DeleteMapping("/{id}")
    public Result deleteApp(@PathVariable("id") Long appId) {
        UserDTO user = UserHolder.getUser();
        boolean ok = platformService.deleteApp(appId, user.getId());
        return ok ? Result.ok() : Result.fail("应用不存在");
    }
}
