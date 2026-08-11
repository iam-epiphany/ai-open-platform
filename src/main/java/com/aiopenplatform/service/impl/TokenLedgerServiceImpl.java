package com.aiopenplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.entity.TokenLedger;
import com.aiopenplatform.mapper.TokenLedgerMapper;
import com.aiopenplatform.service.ITokenLedgerService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Token 账本服务实现
 * </p>
 */
@Service
public class TokenLedgerServiceImpl extends ServiceImpl<TokenLedgerMapper, TokenLedger> implements ITokenLedgerService {

    @Override
    public IPage<TokenLedger> pageRecords(Long userId, Integer changeType, int current, int size) {
        LambdaQueryWrapper<TokenLedger> wrapper = new LambdaQueryWrapper<TokenLedger>()
                .eq(TokenLedger::getUserId, userId)
                .eq(changeType != null, TokenLedger::getChangeType, changeType)
                .orderByDesc(TokenLedger::getCreateTime);
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public List<Map<String, Object>> sumConsumeByDay(Long userId, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days - 1L).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return getBaseMapper().sumConsumeByDay(userId, startTime);
    }
}
