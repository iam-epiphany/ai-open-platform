package com.aiopenplatform.service;

import com.aiopenplatform.dto.Result;
import com.aiopenplatform.utils.RedisIdWorker;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Credits activity hot path: Lua reserves stock and writes a Stream message; the consumer grants asynchronously. */
@Slf4j
@Service
public class CreditActivityService {
    private static final String STREAM = "credit:grant:stream";
    private static final String GROUP = "credit-grant-group";
    private static final String CONSUMER = "credit-grant-c1";
    private static final DefaultRedisScript<Long> GRANT = script("lua/grant.lua");
    private static final DefaultRedisScript<Long> ROLLBACK = script("lua/rollback_credit_grant.lua");

    @Resource private StringRedisTemplate redis;
    @Resource private JdbcTemplate jdbc;
    @Resource private RedisIdWorker idWorker;
    @Resource private RedissonClient redisson;

    @PostConstruct
    public void initialize() {
        for (Map<String, Object> p : jdbc.queryForList("SELECT id,stock FROM tb_credit_package WHERE status=1")) {
            redis.opsForValue().set(stockKey(id(p, "id")), String.valueOf(id(p, "stock")));
        }
        try {
            redis.opsForStream().createGroup(STREAM, GROUP);
        } catch (RedisSystemException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("BUSYGROUP")) {
                try {
                    RecordId init = redis.opsForStream().add(STREAM, Collections.singletonMap("init", "1"));
                    redis.opsForStream().createGroup(STREAM, GROUP);
                    redis.opsForStream().delete(STREAM, init);
                } catch (Exception createEx) { log.warn("Credits Stream 消费组创建失败", createEx); }
            }
        }
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT a.id activity_id,a.title,a.begin_time,a.end_time,p.id package_id,p.package_name,p.credit_amount,p.stock,p.limit_count "
                + "FROM tb_credit_activity a JOIN tb_credit_package p ON p.activity_id=a.id WHERE a.status=1 AND p.status=1 ORDER BY a.id,p.id");
    }

    public Result claim(Long packageId) {
        Map<String, Object> p;
        try { p = jdbc.queryForMap("SELECT p.id,p.limit_count,p.status,a.begin_time,a.end_time,a.status activity_status FROM tb_credit_package p JOIN tb_credit_activity a ON a.id=p.activity_id WHERE p.id=?", packageId); }
        catch (Exception e) { return Result.fail("Credits 包不存在"); }
        if (id(p, "status") != 1 || id(p, "activity_status") != 1) return Result.fail("活动已下线");
        int limit = (int) Math.max(1L, id(p, "limit_count"));
        long userId = UserHolder.getUser().getId();
        long orderId = idWorker.nextId("credit-order");
        Long outcome = redis.execute(GRANT, Arrays.asList(stockKey(packageId), claimedKey(packageId), countKey(packageId, userId), STREAM),
                String.valueOf(packageId), String.valueOf(userId), String.valueOf(orderId), String.valueOf(limit));
        if (outcome == null || outcome < 0) return Result.fail(outcome != null && outcome == -1 ? "库存不足" : outcome != null && outcome == -2 ? "不能重复领取" : "超出限购或系统繁忙");
        Map<String, Object> result = new HashMap<>(); result.put("orderId", orderId); result.put("remainStock", outcome);
        return Result.ok(result);
    }

    @Scheduled(fixedDelay = 150, initialDelay = 6000)
    public void consume() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(GROUP, CONSUMER), StreamOffset.create(STREAM, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> v = record.getValue();
            if (v.containsKey("init")) { redis.opsForStream().acknowledge(STREAM, GROUP, record.getId()); continue; }
            try { grant(record.getId().getValue(), Long.valueOf(String.valueOf(v.get("orderId"))), Long.valueOf(String.valueOf(v.get("skuId"))), Long.valueOf(String.valueOf(v.get("userId"))), Integer.parseInt(String.valueOf(v.get("limitCount")))); }
            catch (Exception ex) { log.error("Credits Stream 发放失败，将保留 pending 重试: {}", record.getId(), ex); }
        }
    }

    private void grant(String messageId, long orderId, long packageId, long userId, int limit) {
        RLock lock = redisson.getLock("lock:credit:grant:" + userId);
        try {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) throw new IllegalStateException("获取用户 Credits 锁失败");
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM tb_credit_order WHERE id=?", Integer.class, orderId);
            if (exists != null && exists > 0) { ack(messageId); return; }
            Map<String, Object> p = jdbc.queryForMap("SELECT credit_amount,limit_count FROM tb_credit_package WHERE id=?", packageId);
            Integer claimed = jdbc.queryForObject("SELECT COUNT(*) FROM tb_credit_order WHERE user_id=? AND package_id=?", Integer.class, userId, packageId);
            boolean accepted = claimed != null && claimed < limit && jdbc.update("UPDATE tb_credit_package SET stock=stock-1 WHERE id=? AND stock>0", packageId) == 1;
            if (!accepted) { rollback(packageId, userId, limit); ack(messageId); return; }
            long amount = id(p, "credit_amount");
            jdbc.update("INSERT INTO tb_credit_order(id,user_id,package_id,credit_amount,status,create_time,grant_time) VALUES(?,?,?,?,1,NOW(),NOW())", orderId, userId, packageId, amount);
            jdbc.update("INSERT IGNORE INTO tb_credit_account(user_id,balance,frozen_balance,update_time) VALUES(?,0,0,NOW())", userId);
            jdbc.update("UPDATE tb_credit_account SET balance=balance+?,update_time=NOW() WHERE user_id=?", amount, userId);
            Long balance = jdbc.queryForObject("SELECT balance FROM tb_credit_account WHERE user_id=?", Long.class, userId);
            jdbc.update("INSERT INTO tb_credit_ledger(user_id,change_type,change_amount,balance_after,reference_no,remark,create_time) VALUES(?,'ACTIVITY_GRANT',?,?,?,'活动领取',NOW())", userId, amount, balance, String.valueOf(orderId));
            ack(messageId);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private void ack(String messageId) { redis.opsForStream().acknowledge(STREAM, GROUP, messageId); }
    private void rollback(long packageId, long userId, int limit) { redis.execute(ROLLBACK, Arrays.asList(stockKey(packageId), claimedKey(packageId), countKey(packageId, userId)), String.valueOf(userId), String.valueOf(limit)); }
    private static DefaultRedisScript<Long> script(String resource) { DefaultRedisScript<Long> s = new DefaultRedisScript<>(); s.setLocation(new ClassPathResource(resource)); s.setResultType(Long.class); return s; }
    private String stockKey(long id) { return "credit:stock:" + id; }
    private String claimedKey(long id) { return "credit:granted:" + id; }
    private String countKey(long id, long user) { return "credit:count:" + id + ":" + user; }
    private long id(Map<String,Object> row, String column) { return ((Number) row.get(column)).longValue(); }
}
