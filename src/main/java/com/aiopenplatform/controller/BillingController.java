package com.aiopenplatform.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.entity.TokenLedger;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.entity.UserQuota;
import com.aiopenplatform.service.ITokenCallLogService;
import com.aiopenplatform.service.ITokenLedgerService;
import com.aiopenplatform.service.ITokenSkuService;
import com.aiopenplatform.service.IUserQuotaService;
import com.aiopenplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单统计：余额池总览 / 账本流水 / 每日消耗
 * <p>
 * 数据源：余额取 tb_user_quota（模型维度），消耗统计取 tb_token_call_log（调用日志），
 * 流水取 tb_token_ledger（账本，发放 + 消耗同表）。
 * </p>
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

    @Resource
    private IUserQuotaService userQuotaService;
    @Resource
    private ITokenLedgerService tokenLedgerService;
    @Resource
    private ITokenCallLogService callLogService;
    @Resource
    private ITokenSkuService tokenSkuService;

    /**
     * 账单总览：各额度池余额 + 今日/近 30 天/累计消耗
     */
    @GetMapping("/summary")
    public Result summary() {
        UserDTO user = UserHolder.getUser();
        Map<String, Object> data = new HashMap<>();
        // 1. 额度池（全部 modelId，直查库）
        List<UserQuota> quotas = userQuotaService.lambdaQuery()
                .eq(UserQuota::getUserId, user.getId())
                .orderByAsc(UserQuota::getModelId)
                .list();
        Map<Long, String> modelNames = loadModelNames();
        List<Map<String, Object>> pools = new ArrayList<>();
        for (UserQuota q : quotas) {
            Map<String, Object> pool = new HashMap<>();
            pool.put("modelId", q.getModelId());
            pool.put("modelName", q.getModelId() == 0 ? "通用额度池" : modelNames.getOrDefault(q.getModelId(), "model-" + q.getModelId()));
            pool.put("balance", q.getBalance());
            pool.put("totalTokens", q.getTotalTokens());
            pool.put("usedTokens", q.getUsedTokens());
            pools.add(pool);
        }
        data.put("pools", pools);
        // 2. 消耗统计（调用日志聚合）
        data.put("todayTokens", sumDay(user.getId(), 1));
        data.put("monthTokens", sumDay(user.getId(), 30));
        data.put("totalTokens", callLogService.sumTokensByUser(user.getId()));
        return Result.ok(data);
    }

    /**
     * 账本流水（changeType 为空查全部）
     */
    @GetMapping("/records")
    public Result records(@RequestParam(value = "changeType", required = false) Integer changeType,
                          @RequestParam(value = "current", defaultValue = "1") int current,
                          @RequestParam(value = "size", defaultValue = "10") int size) {
        UserDTO user = UserHolder.getUser();
        IPage<TokenLedger> page = tokenLedgerService.pageRecords(user.getId(), changeType, current, size);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /**
     * 近 N 天每日消耗（供账单图表）
     */
    @GetMapping("/daily")
    public Result daily(@RequestParam(value = "days", defaultValue = "7") int days) {
        UserDTO user = UserHolder.getUser();
        if (days < 1 || days > 90) {
            return Result.fail("天数范围 1~90");
        }
        return Result.ok(callLogService.sumByDay(user.getId(), days));
    }

    private long sumDay(Long userId, int days) {
        List<Map<String, Object>> list = callLogService.sumByDay(userId, days);
        long total = 0;
        for (Map<String, Object> row : list) {
            total += ((Number) row.get("tokens")).longValue();
        }
        return total;
    }

    private Map<Long, String> loadModelNames() {
        Map<Long, String> names = new HashMap<>();
        List<TokenSku> skus = tokenSkuService.lambdaQuery().eq(TokenSku::getStatus, 1).list();
        for (TokenSku sku : skus) {
            if (sku.getModelId() != null && StrUtil.isNotBlank(sku.getModelName())) {
                names.putIfAbsent(sku.getModelId(), sku.getModelName());
            }
        }
        return names;
    }
}
