package com.hmdp.consumer;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.service.impl.VoucherOrderServiceImpl.SECKILL_ORDER_TOPIC;

/**
 * 秒杀订单 Kafka 消费者：异步落库（流量削峰）
 * <p>
 * 可靠性设计：
 * <ul>
 *     <li><b>手动 ACK</b>：处理成功才提交 offset，处理异常不 ACK，由 Kafka 重新投递；</li>
 *     <li><b>幂等</b>：Redisson 分布式锁保证同一用户的订单串行处理，
 *     DB 侧「一人一单」校验 + 乐观锁（stock &gt; 0）扣减库存，重复消息不会重复扣库存；</li>
 *     <li><b>失败补偿</b>：DB 库存不足时回滚 Redis 预扣（恢复库存、移除用户记录、回退限购计数），
 *     避免 Redis 与 DB 库存不一致。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;
    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback_seckill.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @KafkaListener(topics = SECKILL_ORDER_TOPIC, groupId = "seckill-order-consumer", concurrency = "2")
    public void onMessage(String message,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                          Acknowledgment acknowledgment) {
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        Long userId = voucherOrder.getUserId();

        //分布式锁：同一用户订单串行落库，防止并发重复下单
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock;
        try {
            isLock = lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取分布式锁被中断", e);
        }
        if (!isLock) {
            //未获取到锁：不 ACK，交给 Kafka 重新投递
            throw new RuntimeException("获取分布式锁失败，稍后重试: userId=" + userId);
        }
        try {
            //幂等落库：返回 false 表示 DB 库存不足
            boolean success = voucherOrderService.createVoucherOrder(voucherOrder);
            if (!success) {
                //补偿回滚 Redis 预扣，保持库存一致
                rollbackSeckill(voucherOrder);
            }
            acknowledgment.acknowledge();
            log.info("秒杀订单落库成功: id={}, userId={}, voucherId={}",
                    voucherOrder.getId(), userId, voucherOrder.getVoucherId());
        } catch (Exception e) {
            //不 ACK，由 Kafka 重投处理
            log.error("秒杀订单落库异常，等待Kafka重投: {}", message, e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回滚 Redis 预扣库存与用户记录（原子 Lua）
     */
    private void rollbackSeckill(VoucherOrder voucherOrder) {
        stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                voucherOrder.getVoucherId().toString(), voucherOrder.getUserId().toString());
        log.warn("已回滚秒杀预扣: voucherId={}, userId={}",
                voucherOrder.getVoucherId(), voucherOrder.getUserId());
    }
}
