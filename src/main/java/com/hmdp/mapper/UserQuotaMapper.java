package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.UserQuota;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 用户 Token 权益 Mapper
 * </p>
 */
public interface UserQuotaMapper extends BaseMapper<UserQuota> {

    /**
     * 发放权益：原子 upsert（依赖唯一索引 uk_user_model）
     * 已存在则 total_tokens / balance 累加并自增乐观锁版本号，保证并发发放不丢失
     *
     * @return 影响行数
     */
    @Insert("INSERT INTO tb_user_quota (user_id, model_id, total_tokens, used_tokens, balance, version, update_time) " +
            "VALUES (#{userId}, #{modelId}, #{amount}, 0, #{amount}, 1, NOW()) " +
            "ON DUPLICATE KEY UPDATE total_tokens = total_tokens + VALUES(total_tokens), " +
            "balance = balance + VALUES(balance), version = version + 1, update_time = NOW()")
    int upsertGrant(@Param("userId") Long userId, @Param("modelId") Long modelId, @Param("amount") Long amount);
}
