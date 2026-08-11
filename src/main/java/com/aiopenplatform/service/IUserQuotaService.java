package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.entity.UserQuota;

/**
 * <p>
 * 用户 Token 权益服务
 * </p>
 */
public interface IUserQuotaService extends IService<UserQuota> {

    /**
     * 用户权益查询（多级缓存：ScopeCaching -&gt; Caffeine -&gt; Redis -&gt; MySQL）
     *
     * @param userId  用户 id
     * @param modelId 模型 id（0=通用池）
     */
    UserQuota getQuotaWithCache(Long userId, Long modelId);

    /**
     * 发放权益（原子 upsert，同发放事务内调用）
     */
    void grantQuota(Long userId, Long modelId, Long amount);

    /**
     * 消耗扣减：一行条件更新原子完成「余额校验 + 扣减 + version 自增」
     * 余额不足或无权益记录时返回 false（不超扣）；扣减后缓存失效由 binlog 驱动（canal 关闭时兜底手动删）
     *
     * @return 是否扣减成功
     */
    boolean deductQuota(Long userId, Long modelId, Long used);

    /**
     * 直接查库取最新权益（同事务内读取最新余额，供账本写 balance_after）
     */
    UserQuota getQuotaFromDb(Long userId, Long modelId);
}
