package com.aiopenplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aiopenplatform.entity.TokenLedger;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Token 账本 Mapper
 * </p>
 */
public interface TokenLedgerMapper extends BaseMapper<TokenLedger> {

    /**
     * 某用户近 N 天每日消耗（账本 change_type=2 按天聚合，供账单图表）
     */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS day, SUM(change_amount) AS tokens " +
            "FROM tb_token_ledger WHERE user_id = #{userId} AND change_type = 2 AND create_time >= #{startTime} " +
            "GROUP BY day ORDER BY day")
    List<Map<String, Object>> sumConsumeByDay(@Param("userId") Long userId, @Param("startTime") LocalDateTime startTime);

    /**
     * 全局发放总量 / 消耗总量（管理后台总览）
     */
    @Select("SELECT IFNULL(SUM(change_amount), 0) FROM tb_token_ledger WHERE change_type = #{changeType}")
    Long sumByChangeType(@Param("changeType") Integer changeType);
}
