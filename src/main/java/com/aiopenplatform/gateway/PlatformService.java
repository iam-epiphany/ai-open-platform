package com.aiopenplatform.gateway;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aiopenplatform.gateway.dto.ChatMessage;
import com.aiopenplatform.gateway.dto.ChatRequest;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public ApiPrincipal authenticate(String rawKey) {
        if (StrUtil.isBlank(rawKey) || !rawKey.startsWith("tok_")) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT k.user_id, k.app_id FROM tb_api_key k JOIN tb_app a ON a.id=k.app_id "
                        + "WHERE k.key_hash=? AND k.status=1 AND a.status=1 "
                        + "AND (k.expire_time IS NULL OR k.expire_time > NOW())",
                SecureUtil.sha256(rawKey));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        jdbcTemplate.update("UPDATE tb_api_key SET last_used_time=NOW() WHERE key_hash=?", SecureUtil.sha256(rawKey));
        return new ApiPrincipal(longValue(row.get("user_id")), longValue(row.get("app_id")));
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> app : apps) {
            Long id = longValue(app.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("app", app);
            item.put("keys", jdbcTemplate.queryForList(
                    "SELECT id,app_id AS appId,prefix AS keyPrefix,status,expire_time AS expireTime,last_used_time AS lastUsedTime,create_time AS createTime FROM tb_api_key WHERE app_id=? ORDER BY create_time DESC", id));
            item.put("models", jdbcTemplate.queryForList("SELECT m.code,m.display_name AS displayName FROM tb_model m JOIN tb_app_model am ON am.model_id=m.id WHERE am.app_id=? AND m.status=1", id));
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
        Map<String, Object> model = jdbcTemplate.queryForMap(
                "SELECT m.id,m.code,m.provider,p.input_credit_per_1k,p.output_credit_per_1k FROM tb_model m JOIN tb_model_price p ON p.model_id=m.id WHERE m.code=? AND m.status=1",
                request.getModel());
        long estimate = reserveAmount(request, model);
        if (!reserve(principal.getUserId(), estimate)) {
            throw new IllegalStateException("Credits 余额不足");
        }
        long begin = System.currentTimeMillis();
        try {
            ProviderChatResponse response = provider(String.valueOf(model.get("provider"))).chat(request);
            long actual = cost(response.getPromptTokens(), response.getCompletionTokens(), model);
            settle(principal.getUserId(), estimate, actual, request.getModel(), response, begin);
            return openAiResponse(request.getModel(), response);
        } catch (RuntimeException ex) {
            release(principal.getUserId(), estimate);
            writeFailureLog(principal, request.getModel(), System.currentTimeMillis() - begin, ex.getMessage());
            throw ex;
        }
    }

    private void validateRequest(ApiPrincipal principal, ChatRequest request) {
        if (principal == null) throw new IllegalStateException("缺少或无效的 API Key");
        if (request == null || StrUtil.isBlank(request.getModel()) || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("model 和 messages 为必填项");
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            throw new IllegalArgumentException("当前版本暂不支持 stream=true");
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

    private void settle(Long userId, long reserved, long actual, String model, ProviderChatResponse response, long begin) {
        transactionTemplate.execute(status -> {
            ensureAccount(userId);
            int updated = jdbcTemplate.update("UPDATE tb_credit_account SET frozen_balance=frozen_balance-?,balance=balance+?-?,update_time=NOW() WHERE user_id=? AND frozen_balance>=?", reserved, reserved, actual, userId, reserved);
            if (updated != 1) throw new IllegalStateException("Credits 结算失败，请联系管理员");
            long balance = currentBalance(userId);
            String ref = "C" + UUID.randomUUID().toString().replace("-", "");
            writeLedger(userId, "CONSUME", -actual, balance, ref, model);
            jdbcTemplate.update("INSERT INTO tb_ai_call_log(request_id,user_id,app_id,model,prompt_tokens,completion_tokens,credit_cost,latency,status,error_message,create_time) VALUES(?,?,?,?,?,?,?,?,1,'',NOW())",
                    ref, userId, ApiPrincipalHolder.get().getAppId(), model, response.getPromptTokens(), response.getCompletionTokens(), actual, System.currentTimeMillis() - begin);
            return null;
        });
    }

    private void release(Long userId, long reserved) {
        jdbcTemplate.update("UPDATE tb_credit_account SET frozen_balance=frozen_balance-?,balance=balance+?,update_time=NOW() WHERE user_id=? AND frozen_balance>=?", reserved, reserved, userId, reserved);
    }

    private void writeFailureLog(ApiPrincipal principal, String model, long latency, String error) {
        jdbcTemplate.update("INSERT INTO tb_ai_call_log(request_id,user_id,app_id,model,prompt_tokens,completion_tokens,credit_cost,latency,status,error_message,create_time) VALUES(?,?,?,?,0,0,0,?,0,?,NOW())",
                "F" + UUID.randomUUID().toString().replace("-", ""), principal.getUserId(), principal.getAppId(), model, latency, StrUtil.sub(error, 0, 500));
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
