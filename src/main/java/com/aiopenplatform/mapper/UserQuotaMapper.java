package com.aiopenplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aiopenplatform.entity.UserQuota;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 消耗扣减：一行条件更新原子完成「校验余额 ≥ 扣减量 + 扣减 + version 自增」
     * 依赖 MySQL 行锁 + WHERE balance >= used 保证并发下不超扣，无需显式加锁
     *
     * @return 影响行数（0 = 余额不足或无权益记录）
     */
    @Update("UPDATE tb_user_quota SET balance = balance - #{used}, " +
            "used_tokens = used_tokens + #{used}, version = version + 1, update_time = NOW() " +
            "WHERE user_id = #{userId} AND model_id = #{modelId} AND balance >= #{used}")
    int deductQuota(@Param("userId") Long userId, @Param("modelId") Long modelId, @Param("used") Long used);
}
