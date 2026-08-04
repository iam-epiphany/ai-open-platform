package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IUserQuotaService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 用户 Token 权益控制器
 * </p>
 */
@RestController
@RequestMapping("/user-quota")
public class UserQuotaController {

    @Resource
    private IUserQuotaService userQuotaService;

    /**
     * 我的 Token 权益（五级缓存热点读）
     *
     * @param modelId 模型 id，默认 0=通用额度池
     */
    @GetMapping("/me")
    public Result myQuota(@RequestParam(value = "modelId", defaultValue = "0") Long modelId) {
        return Result.ok(userQuotaService.getQuotaWithCache(UserHolder.getUser().getId(), modelId));
    }
}
