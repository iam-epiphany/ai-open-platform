package com.aiopenplatform.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenCallLog;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 模型调用日志服务
 * </p>
 */
public interface ITokenCallLogService extends IService<TokenCallLog> {

    /**
     * 记录一次调用（与扣费同事务落库）
     */
    void saveLog(TokenCallLog log);

    /**
     * 某用户调用日志分页（按时间倒序）
     */
    IPage<TokenCallLog> pageByUser(Long userId, int current, int size);

    /**
     * 全部调用日志分页（管理后台，按时间倒序）
     */
    IPage<TokenCallLog> pageAll(int current, int size);

    /**
     * 某用户近 N 天每日消耗（按天聚合，供账单图表）
     */
    List<Map<String, Object>> sumByDay(Long userId, int days);

    /**
     * 全局消耗总量（管理后台总览）
     */
    Long sumTotalTokens();

    /**
     * 全局调用次数（管理后台总览）
     */
    Long countTotal();

    /**
     * 某用户累计消耗总量（账单总览）
     */
    Long sumTokensByUser(Long userId);
}
