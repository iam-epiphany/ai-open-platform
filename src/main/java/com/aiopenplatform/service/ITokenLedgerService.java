package com.aiopenplatform.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.TokenLedger;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * Token 账本服务
 * </p>
 */
public interface ITokenLedgerService extends IService<TokenLedger> {

    /**
     * 账本流水分页查询（changeType 为空查全部，按创建时间倒序）
     *
     * @param userId     用户 id
     * @param changeType 变动类型：1=发放；2=消耗；null=全部
     */
    IPage<TokenLedger> pageRecords(Long userId, Integer changeType, int current, int size);

    /**
     * 某用户近 N 天每日消耗（账本 change_type=2 按天聚合，供账单图表）
     */
    List<Map<String, Object>> sumConsumeByDay(Long userId, int days);
}
