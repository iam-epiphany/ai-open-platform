package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.TokenActivity;

import java.util.Map;

/**
 * <p>
 * 平台活动页服务
 * </p>
 */
public interface ITokenActivityService extends IService<TokenActivity> {

    /**
     * 活动页聚合数据（活动信息 + SKU 列表，多级缓存）
     */
    Map<String, Object> getActivityDetail(Long id);
}
