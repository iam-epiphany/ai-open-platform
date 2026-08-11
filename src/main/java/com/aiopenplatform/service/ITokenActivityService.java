package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenActivity;

import java.util.List;
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

    /**
     * 在售活动列表（status=1，按开始时间升序），每个活动返回与 {@link #getActivityDetail} 相同的聚合结构
     */
    List<Map<String, Object>> listOnlineActivities();
}
