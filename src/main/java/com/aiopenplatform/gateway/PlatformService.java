package com.aiopenplatform.gateway;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aiopenplatform.gateway.dto.ChatMessage;
import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ChatStreamListener;
import com.aiopenplatform.gateway.dto.ProviderChatResponse;
import com.aiopenplatform.gateway.provider.ModelProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Credits business boundary: apps, API keys, model permissions,
 * reservation/settlement, and the immutable call audit log live here.
 */
@Service
public class PlatformService {

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private Collection<ModelProvider> providers;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private KeyRateLimitService keyRateLimitService;

    public ApiPrincipal authenticate(String rawKey) {
        if (StrUtil.isBlank(rawKey) || !rawKey.startsWith("tok_")) {
            return null;
        }
        String hash = SecureUtil.sha256(rawKey);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT k.user_id, k.app_id, k.id AS key_id FROM tb_api_key k JOIN tb_app a ON a.id=k.app_id "
                        + "WHERE k.key_hash=? AND k.status=1 AND a.status=1 "
                        + "AND (k.expire_time IS NULL OR k.expire_time > NOW())",
                hash);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        // 最近使用时间最多每 5 分钟落库一次，避免每个请求都产生一次写放大
        jdbcTemplate.update("UPDATE tb_api_key SET last_used_time=NOW() WHERE key_hash=? "
                + "AND (last_used_time IS NULL OR last_used_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE))", hash);
        return new ApiPrincipal(longValue(row.get("user_id")), longValue(row.get("app_id")), longValue(row.get("key_id")));
    }

    @Transactional
    public Map<String, Object> createApp(Long userId, String appName, String description) {
        if (StrUtil.isBlank(appName) || appName.trim().length() > 64) {
            throw new IllegalArgumentException("应用名称不能为空且不能超过 64 个字符");
        }
        jdbcTemplate.update("INSERT INTO tb_app(user_id, app_name, description, status, create_time, update_time) VALUES(?,?,?,?,NOW(),NOW())",
                userId, appName.trim(), StrUtil.blankToDefault(description, ""), 1);
        Long appId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO tb_app_model(app_id, model_id) SELECT ?, id FROM tb_model WHERE status=1", appId);
        String key = createKey(appId, userId);
        Map<String, Object> app = jdbcTemplate.queryForMap("SELECT id,user_id,app_name,description,status,create_time FROM tb_app WHERE id=?", appId);
        Map<String, Object> result = new HashMap<>();
        result.put("app", app);
        result.put("apiKeyPlain", key);
        return result;
    }

    public List<Map<String, Object>> listApps(Long userId) {
        List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                "SELECT id,user_id AS userId,app_name AS appName,description,status,create_time AS createTime FROM tb_app WHERE user_id=? ORDER BY create_time DESC", userId);
        if (apps.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> appIds = new ArrayList<>();
        for (Map<String, Object> app : apps) {
            appIds.add(longValue(app.get("id")));
        }
        String marks = String.join(",", Collections.nCopies(appIds.size(), "?"));
        Object[] idArgs = appIds.toArray();

        // 批量取各应用的 Key 与模型授权，避免逐应用 N+1 查询
        Map<Long, List<Map<String, Object>>> keysByApp = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,app_id AS appId,prefix AS keyPrefix,status,expire_time AS expireTime,last_used_time AS lastUsedTime,create_time AS createTime "
                        + "FROM tb_api_key WHERE app_id IN (" + marks + ") ORDER BY create_time DESC", idArgs)) {
            keysByApp.computeIfAbsent(longValue(row.get("appId")), k -> new ArrayList<>()).add(row);
        }
        Map<Long, List<Map<String, Object>>> modelsByApp = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT am.app_id AS appId,m.code,m.display_name AS displayName "
                        + "FROM tb_model m JOIN tb_app_model am ON am.model_id=m.id WHERE am.app_id IN (" + marks + ") AND m.status=1", idArgs)) {
            modelsByApp.computeIfAbsent(longValue(row.get("appId")), k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> app : apps) {
            Long id = longValue(app.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("app", app);
            item.put("keys", keysByApp.getOrDefault(id, Collections.emptyList()));
            item.put("models", modelsByApp.getOrDefault(id, Collections.emptyList()));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public String createKeyForUser(Long appId, Long userId) {
        Integer owned = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_app WHERE id=? AND user_id=? AND status=1", Integer.class, appId, userId);
        if (owned == null || owned == 0) {
            throw new IllegalArgumentException("应用不存在或已停用");
        }
        return createKey(appId, userId);
    }

    @Transactional
    public boolean setKeyStatus(Long keyId, Long userId, int status) {
        return jdbcTemplate.update("UPDATE tb_api_key SET status=?,update_time=NOW() WHERE id=? AND user_id=?", status, keyId, userId) > 0;
    }

    @Transactional
    public boolean deleteApp(Long appId, Long userId) {
        int removed = jdbcTemplate.update("DELETE FROM tb_app WHERE id=? AND user_id=?", appId, userId);
        if (removed > 0) {
            jdbcTemplate.update("DELETE FROM tb_api_key WHERE app_id=?", appId);
            jdbcTemplate.update("DELETE FROM tb_app_model WHERE app_id=?", appId);
        }
        return removed > 0;
    }

    /** High-concurrency activity settlement: grant the claimed package to the canonical Credits account. */
    @Transactional
    public long grantCredits(Long userId, long credits, String referenceNo, String remark) {
        if (userId == null || credits <= 0 || StrUtil.isBlank(referenceNo)) {
            throw new IllegalArgumentException("Credits 发放参数错误");
        }
        addCredits(userId, credits);
        long balance = currentBalance(userId);
        writeLedger(userId, "ACTIVITY_GRANT", credits, balance, referenceNo, StrUtil.blankToDefault(remark, "活动领取"));
        return balance;
    }

    /**
     * 演示环境的 Credits 购买闭环：后端确定套餐价格并立即确认模拟支付，
     * 从而保持「订单、入账、账本」处于同一事务。
     */
    @Transactional
    public Map<String, Object> purchaseCredits(Long userId, long credits) {
        BigDecimal amount = purchaseAmount(credits);
        String orderNo = "P" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO tb_credit_purchase_order(order_no,user_id,credit_amount,payment_amount,status,paid_time,create_time) VALUES(?,?,?,?,1,NOW(),NOW())",
                orderNo, userId, credits, amount);
        addCredits(userId, credits);
        long balance = currentBalance(userId);
        writeLedger(userId, "PURCHASE", credits, balance, orderNo, "模拟支付购买 Credits");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("credits", credits);
        result.put("paymentAmount", amount);
        result.put("balance", balance);
        return result;
    }

    /** Administrator adjustment; a negative amount is protected against overdraft. */
    @Transactional
    public long adjustCredits(Long userId, long signedAmount, String referenceNo, String remark) {
        if (userId == null || signedAmount == 0 || StrUtil.isBlank(referenceNo)) {
            throw new IllegalArgumentException("Credits 调整参数错误");
        }
        ensureAccount(userId);
        lockAccount(userId);
        if (signedAmount > 0) {
            jdbcTemplate.update("UPDATE tb_credit_account SET balance=balance+?,update_time=NOW() WHERE user_id=?", signedAmount, userId);
        } else {
            long deduction = -signedAmount;
            if (jdbcTemplate.update("UPDATE tb_credit_account SET balance=balance-?,update_time=NOW() WHERE user_id=? AND balance>=?", deduction, userId, deduction) != 1) {
                throw new IllegalStateException("用户 Credits 余额不足");
            }
        }
        long balance = currentBalance(userId);
        writeLedger(userId, signedAmount > 0 ? "ADMIN_GRANT" : "ADMIN_DEDUCT", signedAmount, balance,
                referenceNo, StrUtil.blankToDefault(remark, "管理员调整"));
        return balance;
    }

    public Map<String, Object> creditSummary(Long userId) {
        Map<String, Object> data = new LinkedHashMap<>(account(userId));
        data.put("todayConsumed", sumLedger(userId, "DATE(create_time)=CURDATE() AND change_amount<0"));
        data.put("monthConsumed", sumLedger(userId, "create_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) AND change_amount<0"));
        data.put("totalConsumed", sumLedger(userId, "change_amount<0"));
        data.put("totalGranted", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(change_amount),0) FROM tb_credit_ledger WHERE user_id=? AND change_amount>0", Long.class, userId));
        return data;
    }

    public List<Map<String, Object>> creditDaily(Long userId, int days) {
        int safeDays = Math.min(90, Math.max(1, days));
        return jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(create_time,'%Y-%m-%d') day,-SUM(change_amount) credits "
                        + "FROM tb_credit_ledger WHERE user_id=? AND change_amount<0 AND create_time>=DATE_SUB(CURDATE(),INTERVAL ? DAY) "
                        + "GROUP BY DATE(create_time) ORDER BY day", userId, safeDays - 1);
    }

    public Map<String, Object> creditRecords(Long userId, String type, int current, int size) {
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.min(100, Math.max(1, size));
        String filter = StrUtil.isBlank(type) ? "" : " AND change_type=?";
        Object[] countArgs = StrUtil.isBlank(type) ? new Object[]{userId} : new Object[]{userId, type};
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_credit_ledger WHERE user_id=?" + filter, Long.class, countArgs);
        List<Map<String, Object>> records;
        String select = "SELECT id,change_type AS changeType,change_amount AS changeAmount,balance_after AS balanceAfter,"
                + "reference_no AS referenceNo,remark,create_time AS createTime FROM tb_credit_ledger WHERE user_id=?" + filter
                + " ORDER BY create_time DESC LIMIT ?,?";
        if (StrUtil.isBlank(type)) {
            records = jdbcTemplate.queryForList(select, userId, (safeCurrent - 1) * safeSize, safeSize);
        } else {
            records = jdbcTemplate.queryForList(select, userId, type, (safeCurrent - 1) * safeSize, safeSize);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0L : total);
        return result;
    }

    private long sumLedger(Long userId, String condition) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(-SUM(change_amount),0) FROM tb_credit_ledger WHERE user_id=? AND " + condition,
                Long.class, userId);
        return value == null ? 0L : value;
    }

    private BigDecimal purchaseAmount(long credits) {
        if (credits == 1_000L) return new BigDecimal("10.00");
        if (credits == 10_000L) return new BigDecimal("88.00");
        if (credits == 100_000L) return new BigDecimal("800.00");
        throw new IllegalArgumentException("请选择平台提供的 Credits 购买套餐");
    }

    public Map<String, Object> account(Long userId) {
        ensureAccount(userId);
        return jdbcTemplate.queryForMap("SELECT user_id AS userId,balance,frozen_balance AS frozenBalance,update_time AS updateTime FROM tb_credit_account WHERE user_id=?", userId);
    }

    public List<Map<String, Object>> models(Long appId) {
        String sql = appId == null
                ? "SELECT id,code,display_name,provider FROM tb_model WHERE status=1 ORDER BY id"
                : "SELECT m.id,m.code,m.display_name,m.provider FROM tb_model m JOIN tb_app_model am ON am.model_id=m.id WHERE m.status=1 AND am.app_id=? ORDER BY m.id";
        return appId == null ? jdbcTemplate.queryForList(sql) : jdbcTemplate.queryForList(sql, appId);
    }

    public Map<String, Object> chat(ApiPrincipal principal, ChatRequest request) {
        validateRequest(principal, request);
        Map<String, Object> model = loadModel(request.getModel());
        long estimate = reserveAmount(request, model);
        checkTpm(principal, request);
        if (!reserve(principal.getUserId(), estimate)) {
            throw new IllegalStateException("Credits 余额不足");
        }
        long begin = System.currentTimeMillis();
        try {
            ProviderChatResponse response = provider(String.valueOf(model.get("provider"))).chat(request);
            long actual = cost(response.getPromptTokens(), response.getCompletionTokens(), model);
            settle(principal.getUserId(), principal.getAppId(), estimate, actual, request.getModel(),
                    response.getPromptTokens(), response.getCompletionTokens(), System.currentTimeMillis() - begin);
            return openAiResponse(request.getModel(), response);
        } catch (RuntimeException ex) {
            release(principal.getUserId(), estimate);
            writeFailureLog(principal.getUserId(), principal.getAppId(), request.getModel(),
                    System.currentTimeMillis() - begin, ex.getMessage());
            throw ex;
        }
    }

    /** Key 级 TPM 校验：与 Credits 预占共用同一 token 估算口径，超限在预占/调上游之前拒绝。 */
    private void checkTpm(ApiPrincipal principal, ChatRequest request) {
        if (!keyRateLimitService.tryAcquireTpm(principal.getKeyId(), estimatedTokens(request))) {
            throw new ApiRateLimitException("API Key 分钟 Token 用量超限（TPM），请稍后再试");
        }
    }

    private Map<String, Object> loadModel(String code) {
        return jdbcTemplate.queryForMap(
                "SELECT m.id,m.code,m.provider,p.input_credit_per_1k,p.output_credit_per_1k FROM tb_model m JOIN tb_model_price p ON p.model_id=m.id WHERE m.code=? AND m.status=1",
                code);
    }

    /** 与 reserveAmount 同一估算口径：输入字符上界 + max_tokens（输出上限），用于 TPM 记账。 */
    private long estimatedTokens(ChatRequest request) {
        int chars = 0;
        for (ChatMessage message : request.getMessages()) {
            chars += message.getContent() == null ? 0 : message.getContent().length();
        }
        return Math.max(1, chars + 256) + request.getMaxTokens();
    }

    private void validateRequest(ApiPrincipal principal, ChatRequest request) {
        if (principal == null) throw new IllegalStateException("缺少或无效的 API Key");
        if (request == null || StrUtil.isBlank(request.getModel()) || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("model 和 messages 为必填项");
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            // 流式输出：结算点后移到流终点（usage 在最后一个 chunk），由 beginStream/executeStream 处理
            return;
        }
        int max = request.getMaxTokens() == null ? 1024 : request.getMaxTokens();
        if (max < 1 || max > 2048) throw new IllegalArgumentException("max_tokens 范围为 1~2048");
        request.setMaxTokens(max);
        Integer permitted = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_model m JOIN tb_app_model am ON am.model_id=m.id WHERE am.app_id=? AND m.code=? AND m.status=1", Integer.class, principal.getAppId(), request.getModel());
        if (permitted == null || permitted == 0) throw new IllegalArgumentException("该应用无权调用指定模型");
    }

    private long reserveAmount(ChatRequest request, Map<String, Object> model) {
        int chars = 0;
        for (ChatMessage message : request.getMessages()) chars += message.getContent() == null ? 0 : message.getContent().length();
        // A character upper bound is intentionally conservative: it covers CJK input
        // (often close to one token per character) plus provider message framing.
        int estimatedPrompt = Math.max(1, chars + 256);
        return cost(estimatedPrompt, request.getMaxTokens(), model);
    }

    private long cost(int prompt, int completion, Map<String, Object> model) {
        return perK(prompt, decimal(model.get("input_credit_per_1k"))) + perK(completion, decimal(model.get("output_credit_per_1k")));
    }

    private long perK(int tokens, BigDecimal price) {
        return BigDecimal.valueOf(tokens).multiply(price).divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING).longValue();
    }

    private boolean reserve(Long userId, long amount) {
        ensureAccount(userId);
        return jdbcTemplate.update("UPDATE tb_credit_account SET balance=balance-?,frozen_balance=frozen_balance+?,update_time=NOW() WHERE user_id=? AND balance>=?", amount, amount, userId, amount) == 1;
    }

    /**
     * 结算：条件 UPDATE 同时校验「冻结额充足」与「结算后余额不为负」（actual 超出预占时不允许扣成负数），
     * 同一事务内写不可变账本（CONSUME）与调用审计。
     */
    private void settle(Long userId, Long appId, long reserved, long actual, String model,
                        int promptTokens, int completionTokens, long latency) {
        transactionTemplate.execute(status -> {
            ensureAccount(userId);
            int updated = jdbcTemplate.update("UPDATE tb_credit_account SET frozen_balance=frozen_balance-?,balance=balance+?-?,update_time=NOW() "
                    + "WHERE user_id=? AND frozen_balance>=? AND balance+?-?>=0", reserved, reserved, actual, userId, reserved, reserved, actual);
            if (updated != 1) throw new IllegalStateException("Credits 结算失败，请联系管理员");
            long balance = currentBalance(userId);
            String ref = "C" + UUID.randomUUID().toString().replace("-", "");
            writeLedger(userId, "CONSUME", -actual, balance, ref, model);
            jdbcTemplate.update("INSERT INTO tb_ai_call_log(request_id,user_id,app_id,model,prompt_tokens,completion_tokens,credit_cost,latency,status,error_message,create_time) VALUES(?,?,?,?,?,?,?,?,1,'',NOW())",
                    ref, userId, appId, model, promptTokens, completionTokens, actual, latency);
            return null;
        });
    }

    private void release(Long userId, long reserved) {
        jdbcTemplate.update("UPDATE tb_credit_account SET frozen_balance=frozen_balance-?,balance=balance+?,update_time=NOW() WHERE user_id=? AND frozen_balance>=?", reserved, reserved, userId, reserved);
    }

    private void writeFailureLog(Long userId, Long appId, String model, long latency, String error) {
        jdbcTemplate.update("INSERT INTO tb_ai_call_log(request_id,user_id,app_id,model,prompt_tokens,completion_tokens,credit_cost,latency,status,error_message,create_time) VALUES(?,?,?,?,0,0,0,?,0,?,NOW())",
                "F" + UUID.randomUUID().toString().replace("-", ""), userId, appId, model, latency, StrUtil.sub(error, 0, 500));
    }

    // ==================== 流式输出（SSE）会话 ====================

    /**
     * 一次流式调用的会话状态：预占金额、上游模型、开始时间。
     * settled 用 CAS 保证「精确结算 / 断连释放」两种结局只发生一次（幂等终态）。
     */
    public static class StreamContext {
        private final Long userId;
        private final Long appId;
        private final ChatRequest request;
        private final Map<String, Object> model;
        private final long estimate;
        private final long beginMs;
        private final AtomicBoolean settled = new AtomicBoolean(false);

        StreamContext(Long userId, Long appId, ChatRequest request, Map<String, Object> model, long estimate, long beginMs) {
            this.userId = userId;
            this.appId = appId;
            this.request = request;
            this.model = model;
            this.estimate = estimate;
            this.beginMs = beginMs;
        }
    }

    /**
     * 流式调用第一阶段（控制器线程同步执行）：参数/模型校验 + TPM 校验 + 预占 Credits。
     * 通过后立即返回 SSE 会话；任何校验失败在此抛出（400/402/429），不会进入流。
     */
    public StreamContext beginStream(ApiPrincipal principal, ChatRequest request) {
        validateRequest(principal, request);
        Map<String, Object> model = loadModel(request.getModel());
        long estimate = reserveAmount(request, model);
        checkTpm(principal, request);
        if (!reserve(principal.getUserId(), estimate)) {
            throw new IllegalStateException("Credits 余额不足");
        }
        return new StreamContext(principal.getUserId(), principal.getAppId(), request, model, estimate, System.currentTimeMillis());
    }

    /**
     * 流式调用第二阶段（异步线程执行）：透传上游增量，usage 只在流终点到达，
     * 上游正常结束后按真实 usage 结算（未回传 usage 时按预占额这一保守上界结算）。
     */
    public void executeStream(StreamContext ctx, ChatStreamListener listener) {
        final int[] usage = new int[2];
        ChatStreamListener bridging = new ChatStreamListener() {
            @Override
            public void onConnected(Runnable abort) {
                listener.onConnected(abort);
            }

            @Override
            public void onDelta(String content) {
                listener.onDelta(content);
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens) {
                usage[0] = promptTokens;
                usage[1] = completionTokens;
                listener.onUsage(promptTokens, completionTokens);
            }

            @Override
            public void onFinish(String finishReason) {
                listener.onFinish(finishReason);
            }
        };
        try {
            provider(String.valueOf(ctx.model.get("provider"))).chatStream(ctx.request, bridging);
            if (ctx.settled.compareAndSet(false, true)) {
                long actual = usage[0] + usage[1] > 0 ? cost(usage[0], usage[1], ctx.model) : ctx.estimate;
                settle(ctx.userId, ctx.appId, ctx.estimate, actual, ctx.request.getModel(),
                        usage[0], usage[1], System.currentTimeMillis() - ctx.beginMs);
            }
        } catch (RuntimeException ex) {
            // 上游中断/读超时：释放预占并记失败审计；若已被断连释放（CAS 已置位）则幂等跳过
            if (ctx.settled.compareAndSet(false, true)) {
                release(ctx.userId, ctx.estimate);
                writeFailureLog(ctx.userId, ctx.appId, ctx.request.getModel(),
                        System.currentTimeMillis() - ctx.beginMs, ex.getMessage());
            }
            throw ex;
        }
    }

    /**
     * 客户端断连/流超时：立即释放预占并记失败审计。
     * 取舍说明：宁可少收不超收（断连时上游 usage 不可得，按预占释放）；
     * 若上游实际已生成 token，成本由平台承担，可接受的演示语义。
     */
    public void abortStream(StreamContext ctx, String reason) {
        if (ctx != null && ctx.settled.compareAndSet(false, true)) {
            release(ctx.userId, ctx.estimate);
            writeFailureLog(ctx.userId, ctx.appId, ctx.request.getModel(),
                    System.currentTimeMillis() - ctx.beginMs, reason);
        }
    }

    private Map<String, Object> openAiResponse(String model, ProviderChatResponse response) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant"); message.put("content", response.getContent());
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0); choice.put("message", message); choice.put("finish_reason", response.getFinishReason());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", response.getPromptTokens()); usage.put("completion_tokens", response.getCompletionTokens()); usage.put("total_tokens", response.getPromptTokens() + response.getCompletionTokens());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")); result.put("object", "chat.completion");
        result.put("created", System.currentTimeMillis() / 1000); result.put("model", model); result.put("choices", java.util.Collections.singletonList(choice)); result.put("usage", usage);
        return result;
    }

    private ModelProvider provider(String providerName) {
        for (ModelProvider provider : providers) if (provider.providerName().equals(providerName)) return provider;
        throw new IllegalStateException("未配置模型供应商：" + providerName);
    }

    private String createKey(Long appId, Long userId) {
        String raw = "tok_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO tb_api_key(app_id,user_id,key_hash,prefix,status,create_time,update_time) VALUES(?,?,?,?,1,NOW(),NOW())", appId, userId, SecureUtil.sha256(raw), raw.substring(0, 12));
        return raw;
    }

    private void ensureAccount(Long userId) { jdbcTemplate.update("INSERT IGNORE INTO tb_credit_account(user_id,balance,frozen_balance,update_time) VALUES(?,0,0,NOW())", userId); }
    private void addCredits(Long userId, Long credits) { ensureAccount(userId); lockAccount(userId); jdbcTemplate.update("UPDATE tb_credit_account SET balance=balance+?,update_time=NOW() WHERE user_id=?", credits, userId); }
    private void lockAccount(Long userId) { jdbcTemplate.queryForObject("SELECT balance FROM tb_credit_account WHERE user_id=? FOR UPDATE", Long.class, userId); }
    private long currentBalance(Long userId) { return jdbcTemplate.queryForObject("SELECT balance FROM tb_credit_account WHERE user_id=?", Long.class, userId); }
    private void writeLedger(Long userId, String type, long change, long balance, String ref, String remark) { jdbcTemplate.update("INSERT INTO tb_credit_ledger(user_id,change_type,change_amount,balance_after,reference_no,remark,create_time) VALUES(?,?,?,?,?,?,NOW())", userId, type, change, balance, ref, remark); }
    private Long longValue(Object value) { return ((Number) value).longValue(); }
    private BigDecimal decimal(Object value) { return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value)); }
}
