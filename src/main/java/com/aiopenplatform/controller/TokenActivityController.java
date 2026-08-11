package com.aiopenplatform.controller;


import com.aiopenplatform.dto.Result;
import com.aiopenplatform.service.ITokenActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 平台活动页控制器（活动页聚合数据为热点读，走五级缓存）
 * </p>
 */
@RestController
@RequestMapping("/token-activity")
public class TokenActivityController {

    @Resource
    private ITokenActivityService tokenActivityService;

    /**
     * 活动页聚合数据：活动信息 + 参与的 Token 包 SKU 列表
     */
    @GetMapping("/{id}")
    public Result getActivity(@PathVariable("id") Long id) {
        return Result.ok(tokenActivityService.getActivityDetail(id));
    }

    /**
     * 在售活动列表（活动 + 各自 Token 包 SKU 聚合），供前端活动列表页使用
     */
    @GetMapping("/list")
    public Result listActivities() {
        return Result.ok(tokenActivityService.listOnlineActivities());
    }
}
