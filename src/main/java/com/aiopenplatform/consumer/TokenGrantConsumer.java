package com.aiopenplatform.consumer;

import com.aiopenplatform.service.ITokenOrderService;
import com.aiopenplatform.service.ITokenOrderService.GrantResult;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_CONSUMER;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_GROUP;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_STREAM_KEY;

/**
 * Token 发放订单 Redis Stream 消费者（异步发放，替代原 Kafka 消费者）
 * <p>
 * 可靠性设计：
 * <ul>
 *     <li><b>手动 ACK</b>：处理成功才 XACK；处理失败不 ACK，消息进入 pending-list，
 *     由 {@link GrantPendingCompensator} 定时补偿重放；</li>
 *     <li><b>幂等</b>：Redisson 分布式锁保证同一用户的发放串行处理，
 *     DB 侧「订单 id 唯一 + 一人一份/限购校验 + 乐观锁（stock &gt; 0）」防止重复发放与超卖；</li>
 *     <li><b>失败补偿</b>：DB 校验不通过（库存不足/已领取）时回滚 Redis 预扣（恢复库存、移除用户、回退计数），
 *     保证 Redis 与 DB 库存一致。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TokenGrantConsumer {

    @Resource
    private ITokenOrderService tokenOrderService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("lua/rollback_grant.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 启动时创建 Stream 消费组（stream 不存在时先插入占位消息再建组）
     */
    @PostConstruct
    public void initStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP);
            log.info("已创建 Stream 消费组: group={}", TOKEN_GRANT_GROUP);
        } catch (RedisSystemException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("Stream 消费组已存在: group={}", TOKEN_GRANT_GROUP);
                return;
            }
            // Stream key 不存在导致建组失败：插入占位消息后建组，再删除占位消息
            try {
                RecordId initId = stringRedisTemplate.opsForStream()
                        .add(TOKEN_GRANT_STREAM_KEY, Collections.singletonMap("init", "1"));
                stringRedisTemplate.opsForStream().createGroup(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP);
                stringRedisTemplate.opsForStream().delete(TOKEN_GRANT_STREAM_KEY, initId);
                log.info("已创建 Stream 消费组（占位建组）: group={}", TOKEN_GRANT_GROUP);
            } catch (Exception ex) {
                log.error("创建 Stream 消费组失败", ex);
            }
        }
    }

    /**
     * 轮询拉取新消息并处理（XREADGROUP），处理成功后手动 XACK
     */
    @Scheduled(fixedDelay = 150, initialDelay = 5000)
    public void consume() {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(TOKEN_GRANT_GROUP, TOKEN_GRANT_CONSUMER),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(TOKEN_GRANT_STREAM_KEY, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            Long orderId = Long.valueOf(String.valueOf(value.get("orderId")));
            Long skuId = Long.valueOf(String.valueOf(value.get("skuId")));
            Long userId = Long.valueOf(String.valueOf(value.get("userId")));
            int limitCount = Integer.parseInt(String.valueOf(value.get("limitCount")));
            processRecord(record.getId().getValue(), orderId, skuId, userId, limitCount);
        }
    }

    /**
     * 处理一条发放消息：Redisson 锁 → 幂等落库 → XACK（消费与补偿共用）
     *
     * @param messageId  Stream 消息 id（用于 ACK）
     * @param orderId    发放订单 id
     * @param skuId      Token 包 SKU id
     * @param userId     用户 id
     * @param limitCount 限购数量（回滚 Redis 预扣时需要）
     */
    public void processRecord(String messageId, Long orderId, Long skuId, Long userId, int limitCount) {
        // 分布式锁：同一用户的发放串行处理，防止并发重复发放
        RLock lock = redissonClient.getLock("lock:token:grant:" + userId);
        boolean isLock;
        try {
            isLock = lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取发放锁被中断: userId=" + userId, e);
        }
        if (!isLock) {
            // 未获取到锁：不 ACK，消息留在 pending-list，交给补偿任务稍后重试
            throw new RuntimeException("获取发放锁失败，稍后重试: userId=" + userId);
        }
        try {
            GrantResult result = tokenOrderService.grantTokenOrder(orderId, skuId, userId);
            if (result == GrantResult.DUPLICATE_ALREADY_GRANTED || result == GrantResult.STOCK_NOT_ENOUGH) {
                // DB 校验不通过（已领取/库存不足）：回滚 Redis 预扣，保持 Redis 与 DB 库存一致
                rollbackGrant(skuId, userId, limitCount);
            }
            // 处理完成（成功/重复消息/已回滚），ACK
            stringRedisTemplate.opsForStream().acknowledge(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP, messageId);
            log.info("发放消息处理完成并 ACK: messageId={}, orderId={}, result={}", messageId, orderId, result);
        } catch (Exception e) {
            // 不 ACK：消息进入 pending-list，由补偿任务重放
            log.error("发放消息处理异常，等待补偿重试: orderId={}", orderId, e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回滚 Redis 预扣库存与用户记录（原子 Lua）
     */
    private void rollbackGrant(Long skuId, Long userId, int limitCount) {
        stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                String.valueOf(skuId), String.valueOf(userId), String.valueOf(limitCount));
        log.warn("已回滚 Redis 预扣: skuId={}, userId={}", skuId, userId);
    }
}
