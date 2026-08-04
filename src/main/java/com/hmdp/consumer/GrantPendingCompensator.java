package com.hmdp.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.TOKEN_GRANT_CONSUMER;
import static com.hmdp.utils.RedisConstants.TOKEN_GRANT_GROUP;
import static com.hmdp.utils.RedisConstants.TOKEN_GRANT_STREAM_KEY;

/**
 * Redis Stream pending-list 补偿任务
 * <p>
 * 消费端处理失败不 ACK 的消息会滞留 pending-list；
 * 本任务周期性扫描，认领（XCLAIM）闲置超过阈值的消息重新处理；
 * 同一消息投递超过 {@link #MAX_DELIVERY} 次仍失败则 ACK 丢弃并记错误日志（死信留痕），防止无限重试。
 * </p>
 */
@Slf4j
@Component
public class GrantPendingCompensator {

    /** 补偿阈值：pending 消息闲置超过该时长（秒）才认领 */
    private static final long MIN_IDLE_SECONDS = 60;
    /** 单条消息最大投递次数，超过则视为死信 */
    private static final int MAX_DELIVERY = 3;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TokenGrantConsumer tokenGrantConsumer;

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void compensate() {
        // XPENDING 概要：无 pending 消息直接返回
        PendingMessagesSummary summary = stringRedisTemplate.opsForStream()
                .pending(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }

        // 分页拉取 pending 消息（按 idle 时间升序）
        PendingMessages pending = stringRedisTemplate.opsForStream()
                .pending(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP, Range.unbounded(), 100);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        for (PendingMessage msg : pending) {
            // 死信处理：投递次数超限，ACK 丢弃并告警
            if (msg.getTotalDeliveryCount() > MAX_DELIVERY) {
                stringRedisTemplate.opsForStream()
                        .acknowledge(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP, msg.getId());
                log.error("发放消息投递 {} 次仍失败，ACK 丢弃（死信）: messageId={}, consumer={}",
                        msg.getTotalDeliveryCount(), msg.getId().getValue(), msg.getConsumerName());
                continue;
            }
            // 闲置超过阈值：XCLAIM 认领后重新处理
            if (msg.getElapsedTimeSinceLastDelivery() != null
                    && msg.getElapsedTimeSinceLastDelivery().getSeconds() >= MIN_IDLE_SECONDS) {
                try {
                    List<ByteRecord> claimed = stringRedisTemplate.execute(
                            (org.springframework.data.redis.core.RedisCallback<List<ByteRecord>>) connection ->
                                    connection.streamCommands().xClaim(
                                            TOKEN_GRANT_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                                            TOKEN_GRANT_GROUP,
                                            TOKEN_GRANT_CONSUMER,
                                            Duration.ofSeconds(MIN_IDLE_SECONDS),
                                            msg.getId()));
                    if (claimed != null) {
                        for (ByteRecord record : claimed) {
                            // ByteRecord 转字段（StringRedisTemplate 值本就是字符串）
                            Map<Object, Object> value = new HashMap<>();
                            record.getValue().forEach((k, v) -> value.put(new String(k), new String(v)));
                            Long orderId = Long.valueOf(String.valueOf(value.get("orderId")));
                            Long skuId = Long.valueOf(String.valueOf(value.get("skuId")));
                            Long userId = Long.valueOf(String.valueOf(value.get("userId")));
                            int limitCount = Integer.parseInt(String.valueOf(value.get("limitCount")));
                            tokenGrantConsumer.processRecord(
                                    record.getId().getValue(), orderId, skuId, userId, limitCount);
                        }
                    }
                } catch (Exception e) {
                    // 补偿重试仍失败：保留在 pending-list，等待下一轮
                    log.warn("补偿重试仍失败，等待下一轮: messageId={}, err={}", msg.getId().getValue(), e.getMessage());
                }
            }
        }
    }
}
