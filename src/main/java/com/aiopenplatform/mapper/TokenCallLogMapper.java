package com.aiopenplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aiopenplatform.entity.TokenCallLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 模型调用日志 Mapper
 * </p>
 */
public interface TokenCallLogMapper extends BaseMapper<TokenCallLog> {

    /**
     * 某用户近 N 天每日消耗（按天聚合，供账单图表）
     */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS day, SUM(total_tokens) AS tokens " +
            "FROM tb_token_call_log WHERE user_id = #{userId} AND create_time >= #{startTime} " +
            "GROUP BY day ORDER BY day")
    List<Map<String, Object>> sumByDay(@Param("userId") Long userId, @Param("startTime") LocalDateTime startTime);

    /**
     * 全局消耗总量（管理后台总览）
     */
    @Select("SELECT IFNULL(SUM(total_tokens), 0) FROM tb_token_call_log")
    Long sumTotalTokens();

    /**
     * 全局调用次数（管理后台总览）
     */
    @Select("SELECT COUNT(*) FROM tb_token_call_log")
    Long countTotal();

    /**
     * 某用户累计消耗总量
     */
    @Select("SELECT IFNULL(SUM(total_tokens), 0) FROM tb_token_call_log WHERE user_id = #{userId}")
    Long sumTokensByUser(@Param("userId") Long userId);
}
