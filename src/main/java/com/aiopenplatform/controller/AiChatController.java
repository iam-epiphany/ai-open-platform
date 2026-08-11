package com.aiopenplatform.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aiopenplatform.dto.AiChatDTO;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.entity.TokenCallLog;
import com.aiopenplatform.entity.TokenLedger;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.entity.UserQuota;
import com.aiopenplatform.service.ITokenCallLogService;
import com.aiopenplatform.service.ITokenLedgerService;
import com.aiopenplatform.service.ITokenSkuService;
import com.aiopenplatform.service.IUserQuotaService;
import com.aiopenplatform.utils.RedisIdWorker;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.AI_MODEL_LIST_KEY;
import static com.aiopenplatform.utils.RedisConstants.AI_MODEL_LIST_TTL;
import static com.aiopenplatform.utils.RedisConstants.AI_REQ_ID_KEY;
import static com.aiopenplatform.utils.RedisConstants.AI_REQ_ID_TTL;

/**
 * AI 开放接口：模型调用（模拟计费）
 * <p>
 * 调用链路：幂等（Redis SETNX）→ 模型校验 → 余额预检（五级缓存读）→ token 估算 →
 * 模拟生成回复 → 事务内「乐观锁扣减余额 + 写账本（change_type=2）+ 写调用日志」。
 * 余额扣减用一行条件更新（balance >= used）保证并发不超扣；扣减后权益缓存由 binlog（Canal）自动失效。
 * </p>
 * 鉴权：/ai/** 由 ApiKeyInterceptor 双通道鉴权（登录态 authorization 或 X-Api-Key）。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiChatController {

    @Resource
    private ITokenSkuService tokenSkuService;
    @Resource
    private IUserQuotaService userQuotaService;
    @Resource
    private ITokenLedgerService tokenLedgerService;
    @Resource
    private ITokenCallLogService callLogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 模型目录（在售 SKU 按 modelId 去重；Redis 短缓存 Cache Aside）
     */
    @GetMapping("/models")
    public Result listModels() {
        String cached = stringRedisTemplate.opsForValue().get(AI_MODEL_LIST_KEY);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(JSONUtil.parseArray(cached));
        }
        List<TokenSku> skus = tokenSkuService.lambdaQuery().eq(TokenSku::getStatus, 1).list();
        Map<Long, String> modelMap = new LinkedHashMap<>();
        for (TokenSku sku : skus) {
            if (sku.getModelId() != null && sku.getModelId() > 0) {
                modelMap.putIfAbsent(sku.getModelId(), sku.getModelName());
            }
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map.Entry<Long, String> entry : modelMap.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("modelId", entry.getKey());
            m.put("modelName", entry.getValue());
            models.add(m);
        }
        stringRedisTemplate.opsForValue().set(AI_MODEL_LIST_KEY, JSONUtil.toJsonStr(models),
                AI_MODEL_LIST_TTL, TimeUnit.SECONDS);
        return Result.ok(models);
    }

    /**
     * 模型调用（模拟计费）
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatDTO dto) {
        UserDTO user = UserHolder.getUser();
        // 1. 幂等：同一 requestId 60s 内重复请求直接拒绝（防连点/防重放）
        if (StrUtil.isNotBlank(dto.getRequestId())) {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(AI_REQ_ID_KEY + dto.getRequestId(), "1", AI_REQ_ID_TTL, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(first)) {
                return Result.fail("重复请求，请勿重复提交");
            }
        }
        // 2. 参数校验
        if (dto.getModelId() == null || dto.getModelId() <= 0) {
            return Result.fail("请选择模型");
        }
        if (StrUtil.isBlank(dto.getPrompt())) {
            return Result.fail("请输入提示词");
        }
        // 3. 模型校验 + 名称快照
        String modelName = resolveModelName(dto.getModelId());
        if (modelName == null) {
            return Result.fail("模型不存在或已下架");
        }
        // 4. Token 估算（输入≈字符数/4，输出按上限）
        int promptTokens = estimatePromptTokens(dto.getPrompt());
        int completionTokens = estimateCompletionTokens(dto.getMaxTokens());
        long used = promptTokens + completionTokens;
        // 5. 余额预检（五级缓存读，快速失败；最终裁决在事务内的 DB 条件更新）
        UserQuota quota = userQuotaService.getQuotaWithCache(user.getId(), dto.getModelId());
        if (quota == null || quota.getBalance() < used) {
            return Result.fail("余额不足，本次调用需要 " + used + " Tokens，请先领取 Token 包");
        }
        // 6. 模拟生成回复
        String reply = mockReply(dto.getPrompt(), modelName);
        // 7. 事务内扣费 + 账本 + 调用日志（AopContext 代理调用使 @Transactional 生效）
        try {
            AiChatController proxy = (AiChatController) AopContext.currentProxy();
            Map<String, Object> result = proxy.doCharge(user.getId(), dto.getModelId(), modelName,
                    promptTokens, completionTokens, used, dto.getRequestId());
            result.put("reply", reply);
            result.put("modelName", modelName);
            return Result.ok(result);
        } catch (IllegalStateException e) {
            // 预检通过但事务内扣减失败（并发竞争）：余额已被其他请求耗尽
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 扣费事务：乐观锁扣减余额 → 回读余额写账本（change_type=2）→ 写调用日志
     * 三个写操作同事务，任何一步失败整体回滚，保证账实一致
     */
    @Transactional
    public Map<String, Object> doCharge(Long userId, Long modelId, String modelName,
                                        int promptTokens, int completionTokens, long used, String requestId) {
        boolean deducted = userQuotaService.deductQuota(userId, modelId, used);
        if (!deducted) {
            throw new IllegalStateException("余额不足，请先领取 Token 包");
        }
        // 同事务回读最新余额，作为账本 balance_after
        UserQuota quota = userQuotaService.getQuotaFromDb(userId, modelId);
        // 账本流水（消耗）
        TokenLedger ledger = new TokenLedger();
        ledger.setUserId(userId);
        ledger.setOrderId(0L);
        ledger.setChangeType(2);
        ledger.setChangeAmount(used);
        ledger.setBalanceAfter(quota == null ? 0L : quota.getBalance());
        tokenLedgerService.save(ledger);
        // 调用日志
        TokenCallLog callLog = new TokenCallLog();
        callLog.setId(redisIdWorker.nextId("call-log"));
        callLog.setUserId(userId);
        callLog.setModelId(modelId);
        callLog.setModelName(modelName);
        callLog.setPromptTokens(promptTokens);
        callLog.setCompletionTokens(completionTokens);
        callLog.setTotalTokens((int) used);
        callLog.setChannel(1);
        callLog.setRequestId(requestId == null ? "" : requestId);
        callLogService.saveLog(callLog);
        log.info("AI 调用计费: userId={}, modelId={}, used={}, balanceAfter={}", userId, modelId, used,
                ledger.getBalanceAfter());
        Map<String, Object> result = new HashMap<>();
        result.put("promptTokens", promptTokens);
        result.put("completionTokens", completionTokens);
        result.put("totalTokens", used);
        result.put("balanceAfter", ledger.getBalanceAfter());
        return result;
    }

    /**
     * 从模型目录解析模型名称（目录本身有 30s 缓存，此处直接查库保证一致性）
     */
    private String resolveModelName(Long modelId) {
        List<TokenSku> skus = tokenSkuService.lambdaQuery().eq(TokenSku::getStatus, 1).list();
        for (TokenSku sku : skus) {
            if (modelId.equals(sku.getModelId()) && StrUtil.isNotBlank(sku.getModelName())) {
                return sku.getModelName();
            }
        }
        return null;
    }

    private int estimatePromptTokens(String prompt) {
        return Math.max(1, (int) Math.ceil(prompt.length() / 4.0));
    }

    private int estimateCompletionTokens(Integer maxTokens) {
        int t = maxTokens == null ? 256 : maxTokens;
        return Math.max(1, Math.min(2048, t));
    }

    /**
     * 模拟回复：本地模板生成（演示计费用，接入真实模型时替换此方法即可）
     */
    private String mockReply(String prompt, String modelName) {
        String preview = StrUtil.sub(prompt, 0, 80);
        return "【" + modelName + " 模拟回复】\n"
                + "已收到你的问题：「" + preview + "」\n\n"
                + "当前为本地模拟计费环境：回复内容由模板生成，Token 消耗按「输入字符数 ÷ 4 + 输出上限」估算，"
                + "扣费明细已实时计入账单。后续接入真实模型接口时，仅需替换本方法的实现。";
    }
}
