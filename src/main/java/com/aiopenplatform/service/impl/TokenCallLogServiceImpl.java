package com.aiopenplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.entity.TokenCallLog;
import com.aiopenplatform.mapper.TokenCallLogMapper;
import com.aiopenplatform.service.ITokenCallLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 模型调用日志服务实现
 * </p>
 */
@Service
public class TokenCallLogServiceImpl extends ServiceImpl<TokenCallLogMapper, TokenCallLog> implements ITokenCallLogService {

    @Override
    public void saveLog(TokenCallLog log) {
        save(log);
    }

    @Override
    public IPage<TokenCallLog> pageByUser(Long userId, int current, int size) {
        LambdaQueryWrapper<TokenCallLog> wrapper = new LambdaQueryWrapper<TokenCallLog>()
                .eq(TokenCallLog::getUserId, userId)
                .orderByDesc(TokenCallLog::getCreateTime);
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public IPage<TokenCallLog> pageAll(int current, int size) {
        return page(new Page<>(current, size),
                new LambdaQueryWrapper<TokenCallLog>().orderByDesc(TokenCallLog::getCreateTime));
    }

    @Override
    public List<Map<String, Object>> sumByDay(Long userId, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days - 1L).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return getBaseMapper().sumByDay(userId, startTime);
    }

    @Override
    public Long sumTotalTokens() {
        return getBaseMapper().sumTotalTokens();
    }

    @Override
    public Long countTotal() {
        return getBaseMapper().countTotal();
    }

    @Override
    public Long sumTokensByUser(Long userId) {
        return getBaseMapper().sumTokensByUser(userId);
    }
}
