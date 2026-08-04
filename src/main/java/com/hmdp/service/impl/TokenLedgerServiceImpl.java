package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.TokenLedger;
import com.hmdp.mapper.TokenLedgerMapper;
import com.hmdp.service.ITokenLedgerService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * Token 账本服务实现
 * </p>
 */
@Service
public class TokenLedgerServiceImpl extends ServiceImpl<TokenLedgerMapper, TokenLedger> implements ITokenLedgerService {

}
